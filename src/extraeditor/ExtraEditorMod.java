package extraeditor;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.ScissorStack;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.math.geom.Bresenham2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Slider;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ScrollPane;
import arc.scene.style.Drawable;
import arc.struct.IntSet;
import arc.struct.LongSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Reflect;
import mindustry.content.Blocks;
import mindustry.editor.EditorTool;
import mindustry.editor.MapView;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.gen.TileOp;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OreBlock;

import static mindustry.Vars.*;

import java.util.Collections;
import arc.scene.ui.TextField.*;
import java.util.HashSet;
import java.util.Set;

public class ExtraEditorMod extends Mod{
    private Mode mode = Mode.none;
    private Shape brushShape = Shape.circle;
    private int brushRadius = 1;
    private Clip clipboard;

    private boolean selecting;
    private int selectStartX, selectStartY, selectEndX, selectEndY;
    private boolean drawingLine;
    private int lineStartX, lineStartY, lineEndX, lineEndY;
    private int dragLastX, dragLastY;
    private boolean dragLastValid;
    private boolean copyFloor = true, copyOre = true, copyBlock = true;
    private boolean overrideBlock = true, overrideOre = true;
    private boolean smartPasting = true;
    private boolean pasteUseEditorTeam = false;
    private boolean eraseBlocks = true, eraseOres = true, eraseTeamOnly = false;
    private boolean replaceMode;
    private Block replaceFrom, replaceTo;

    private boolean advancedGrid;
    private int gridDetail = 0;
    private boolean selectingGridArea;
    private Recti gridArea;
    private int gridStartX, gridStartY, gridEndX, gridEndY;

    private MapView attachedView;
    private EditorTool lastVanillaTool;
    private InputListener viewListener;
    private Table toolbar;
    private Table menuBody;
    private ScrollPane mobileMenuScroll;
    private Table mobilePasteBar;
    private ImageButton collapseButton;
    private Overlay overlay;
    private Table optionsPopup;
    private ImageButton optionsOwner;
    private Slider brushSlider;
    private Slider gridSlider;
    private arc.scene.ui.TextField brushSizeField;
    private arc.scene.ui.TextField gridDetailField;
    private boolean collapsed;
    private ImageButton fromPickButton, toPickButton;
    private final ObjectMap<Mode, ImageButton> toolButtons = new ObjectMap<>();
    // paste progress
    private boolean pastingInProgress = false;
    private float pasteProgress = 0f;
    // track timestamps for operations (weak map so entries don't keep ops alive)
    private java.util.Map<Object, Long> opTimestamps = new java.util.WeakHashMap<>();
    // track reverted operations (don't remove from stack, just mark as reverted)
    private java.util.Set<Object> revertedOperations = Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());
    private boolean showReverted = false;
    private boolean showNormal = true;
    private boolean undoListOpen = false;
    private ImageButton undoToggleBtn;
    private String currentPopupKind;
    private String stashedPopupKind;
    private boolean restoringPopup;
    // settings
    private float ghostOpacity = 0.12f;
    private boolean showGhostOutline = true;
    // mobile paste controls
    private boolean mobilePasteOriginSet;
    private int mobilePasteOriginX, mobilePasteOriginY;
    private boolean mobilePasteDragging;
    private int mobilePasteDragLastX, mobilePasteDragLastY;
    // mobile pinch zoom
    private boolean pinchActive;
    private float pinchLastDist;

    private enum Mode{
        none, select, cut, paste, eraseOre, draw, drawLine
    }

    private enum Shape{
        circle, square, diamond, cross
    }

    public ExtraEditorMod(){
        Events.on(ClientLoadEvent.class, event -> Events.run(Trigger.update, this::update));
        // register settings in Mindustry settings menu after UI initializes
        Events.on(ClientLoadEvent.class, e -> registerSettings());
    }

    private void registerSettings(){
        try{
            if(ui != null && ui.settings != null){
                // Register custom Extra Editor settings category
                ui.settings.addCategory("Extra Editor", Icon.pencil, table -> {
                    mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.SliderSetting s = table.sliderPref("ee-ghost-opacity", 12, 0, 100, 1, v -> v + "%", v -> ghostOpacity = v / 100f);
                    try{ s.title = "Ghost Opacity"; }catch(Exception ignored){}
                    table.checkPref("ee-ghost-outline", showGhostOutline, v -> showGhostOutline = v);
                    try{
                        mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting last = table.getSettings().peek();
                        if(last != null) last.title = "Ghost Outline";
                    }catch(Exception ignored){}
                });
            }
        }catch(Exception e){
            // UI not yet initialized, ignore
        }
    }

    private void update(){
        if(headless || ui == null || ui.editor == null) return;

        if(!ui.editor.isShown()){
            removeUi();
            return;
        }

        MapView view = ui.editor.getView();
        if(view == null){
            removeUi();
            return;
        }

        if(attachedView != view){
            detachViewListener();
            attachedView = view;
            attachViewListener(view);
        }

        if(toolbar == null || toolbar.parent == null){
            buildToolbar();
            ui.editor.addChild(toolbar);
        }
        if(overlay == null || overlay.parent == null){
            overlay = new Overlay();
            overlay.touchable = Touchable.disabled;
            ui.editor.addChild(overlay);
        }

        positionUi();
        handleMobilePinchZoom();
        if(advancedGrid && view.isGrid()){
            view.setGrid(false);
        }
        if(view.isGrid()){
            advancedGrid = false;
        }
        syncModeWithVanillaTool();

        if(Core.input.keyTap(KeyCode.escape)){
            closeOptionsPopup();
            if(selecting || selectingGridArea || drawingLine){
                selecting = false;
                selectingGridArea = false;
                drawingLine = false;
            }else{
                setMode(Mode.none);
            }
        }

        if(optionsPopup != null && (Core.input.keyTap(KeyCode.mouseLeft) || Core.input.keyTap(KeyCode.mouseRight))){
            Element hover = Core.scene.getHoverElement();
            if(!isDescendant(hover, optionsPopup) && (optionsOwner == null || !isDescendant(hover, optionsOwner))){
                closeOptionsPopup();
            }
        }

        // track new DrawOperation timestamps so undo list can show when actions occurred
        try{
            Object stackObj = Reflect.get(mindustry.editor.MapEditor.class, editor, "stack");
            if(stackObj != null){
                @SuppressWarnings("unchecked")
                arc.struct.Seq<mindustry.editor.DrawOperation> seq = (arc.struct.Seq<mindustry.editor.DrawOperation>)Reflect.get(mindustry.editor.OperationStack.class, stackObj, "stack");
                if(seq != null){
                    for(int i = 0; i < seq.size; i++){
                        Object op = seq.get(i);
                        if(op != null && !opTimestamps.containsKey(op)){
                            opTimestamps.put(op, System.currentTimeMillis());
                        }
                    }
                }
            }
        }catch(Exception e){
            // ignore reflection issues
        }

    }

    private void removeUi(){
        if(toolbar != null) toolbar.remove();
        if(overlay != null) overlay.remove();
        if(mobilePasteBar != null) mobilePasteBar.remove();
        closeOptionsPopup();
        detachViewListener();
        toolButtons.clear();
        attachedView = null;
        mode = Mode.none;
        selecting = false;
        drawingLine = false;
        selectingGridArea = false;
    }

    private void attachViewListener(MapView view){
        viewListener = new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(pointer != 0) return false;

                Point2 p = projectRaw(view, x, y);
                if(button == KeyCode.mouseLeft){
                    if(mode == Mode.none && !selectingGridArea) return false;

                    if(selectingGridArea){
                        if(!inBounds(p.x, p.y)) return false;
                        selectingGridArea = true;
                        gridStartX = gridEndX = p.x;
                        gridStartY = gridEndY = p.y;
                        event.cancel();
                        event.stop();
                        return true;
                    }

                    event.cancel();
                    event.stop();
                    if(mode == Mode.select || mode == Mode.cut){
                        selecting = true;
                        selectStartX = selectEndX = p.x;
                        selectStartY = selectEndY = p.y;
                    }else if(mode == Mode.paste){
                        if(mobile){
                            if(clipboard != null && mobilePasteOriginSet){
                                if(insidePasteGhost(p.x, p.y)){
                                    mobilePasteDragging = true;
                                    mobilePasteDragLastX = p.x;
                                    mobilePasteDragLastY = p.y;
                                }
                            }
                        }else{
                            if(!inBounds(p.x, p.y)) return true;
                            Point2 o = pasteOriginForCursor(p.x, p.y);
                            if(clipboard != null){
                                // allow pasting even if part of clipboard is out of bounds; pasteAt will skip OOB cells
                                pasteAt(o.x, o.y);
                            }
                        }
                    }else if(mode == Mode.eraseOre){
                        if(!inBounds(p.x, p.y)) return true;
                        dragLastX = p.x;
                        dragLastY = p.y;
                        dragLastValid = true;
                        eraseOreAt(p.x, p.y);
                    }else if(mode == Mode.draw){
                        if(!inBounds(p.x, p.y)) return true;
                        dragLastX = p.x;
                        dragLastY = p.y;
                        dragLastValid = true;
                        applyBrushDrawAt(p.x, p.y);
                    }else if(mode == Mode.drawLine){
                        if(!inBounds(p.x, p.y)) return true;
                        drawingLine = true;
                        lineStartX = lineEndX = p.x;
                        lineStartY = lineEndY = p.y;
                    }
                    return true;
                }

                return false;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                if(pointer != 0) return;
                Point2 p = projectRaw(view, x, y);
                if(selectingGridArea){
                    gridEndX = Mathf.clamp(p.x, 0, editor.width() - 1);
                    gridEndY = Mathf.clamp(p.y, 0, editor.height() - 1);
                    event.cancel();
                    event.stop();
                    return;
                }
                if(mode == Mode.none) return;
                if(mode == Mode.select || mode == Mode.cut){
                    if(selecting){
                        selectEndX = p.x;
                        selectEndY = p.y;
                        event.cancel();
                        event.stop();
                    }
                }else if(mode == Mode.eraseOre && inBounds(p.x, p.y)){
                    if(dragLastValid){
                        Bresenham2.line(dragLastX, dragLastY, p.x, p.y, (lx, ly) -> {
                            if(inBounds(lx, ly)) eraseOreAt(lx, ly);
                        });
                    }else{
                        eraseOreAt(p.x, p.y);
                    }
                    dragLastX = p.x;
                    dragLastY = p.y;
                    dragLastValid = true;
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.draw && inBounds(p.x, p.y)){
                    if(dragLastValid){
                        Bresenham2.line(dragLastX, dragLastY, p.x, p.y, (lx, ly) -> {
                            if(inBounds(lx, ly)) applyBrushDrawAt(lx, ly);
                        });
                    }else{
                        applyBrushDrawAt(p.x, p.y);
                    }
                    dragLastX = p.x;
                    dragLastY = p.y;
                    dragLastValid = true;
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.drawLine && drawingLine){
                    lineEndX = Mathf.clamp(p.x, 0, editor.width() - 1);
                    lineEndY = Mathf.clamp(p.y, 0, editor.height() - 1);
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.paste && mobile && mobilePasteDragging){
                    int dx = p.x - mobilePasteDragLastX;
                    int dy = p.y - mobilePasteDragLastY;
                    mobilePasteOriginX += dx;
                    mobilePasteOriginY += dy;
                    mobilePasteDragLastX = p.x;
                    mobilePasteDragLastY = p.y;
                    event.cancel();
                    event.stop();
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(pointer != 0 || button != KeyCode.mouseLeft) return;
                Point2 p = projectRaw(view, x, y);

                if(selectingGridArea){
                    gridEndX = Mathf.clamp(p.x, 0, editor.width() - 1);
                    gridEndY = Mathf.clamp(p.y, 0, editor.height() - 1);
                    setGridAreaFromSelection();
                    selectingGridArea = false;
                    event.cancel();
                    event.stop();
                    return;
                }

                if(mode == Mode.none) return;
                if(mode == Mode.select || mode == Mode.cut){
                    if(selecting){
                        selectEndX = p.x;
                        selectEndY = p.y;
                        copySelection(mode == Mode.cut);
                        selecting = false;
                        if(mode == Mode.select){
                            setMode(Mode.none);
                            if(attachedView != null) attachedView.setTool(lastVanillaTool == null ? EditorTool.pick : lastVanillaTool);
                        }
                        else setMode(Mode.paste);
                        event.cancel();
                        event.stop();
                    }
                }else if(mode == Mode.eraseOre){
                    dragLastValid = false;
                    editor.flushOp();
                    ui.editor.resetSaved();
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.draw){
                    dragLastValid = false;
                    editor.flushOp();
                    ui.editor.resetSaved();
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.drawLine && drawingLine){
                    lineEndX = Mathf.clamp(p.x, 0, editor.width() - 1);
                    lineEndY = Mathf.clamp(p.y, 0, editor.height() - 1);
                    applyLineDraw();
                    drawingLine = false;
                    editor.flushOp();
                    ui.editor.resetSaved();
                    event.cancel();
                    event.stop();
                }else if(mode == Mode.paste && mobile){
                    mobilePasteDragging = false;
                    event.cancel();
                    event.stop();
                }
            }
        };
        view.addCaptureListener(viewListener);
    }

    private void detachViewListener(){
        if(attachedView != null && viewListener != null){
            attachedView.removeCaptureListener(viewListener);
        }
        viewListener = null;
    }

    private void buildToolbar(){
        toolButtons.clear();
        toolbar = new Table();
        toolbar.touchable = Touchable.enabled;
        toolbar.defaults().left().pad(2f);

        collapseButton = toolbar.button(iconByName(collapsed ? "extraeditor-show" : "extraeditor-hide", Icon.leftOpen), Styles.flati, () -> {
            if(!collapsed){
                stashPopupState();
                closeOptionsPopup();
            }
            collapsed = !collapsed;
            rebuildToolbar();
            if(!collapsed){
                restoreStashedPopup();
            }
        }).size(32f).tooltip("Hide / Show").get();
        collapseButton.getImageCell().size(24f);
        collapseButton.update(() -> collapseButton.getImage().setDrawable(iconByName(collapsed ? "extraeditor-show" : "extraeditor-hide", collapsed ? Icon.rightOpen : Icon.leftOpen)));
        toolbar.row();

        if(collapsed){
            toolbar.pack();
            return;
        }

        menuBody = new Table(Tex.button);
        menuBody.defaults().size(44f).pad(2f);
        if(mobile){
            mobileMenuScroll = new ScrollPane(menuBody);
            mobileMenuScroll.setFadeScrollBars(false);
            mobileMenuScroll.setScrollingDisabled(true, false);
            mobileMenuScroll.setOverscroll(false, true);
            float mh = 420f;
            if(ui != null && ui.editor != null){
                mh = Math.min(520f, Math.max(220f, ui.editor.getHeight() - 90f));
            }
            toolbar.add(mobileMenuScroll).height(mh);
        }else{
            toolbar.add(menuBody);
        }

        ImageButton select = toolButton(Icon.copy, "Select/Copy", Mode.select);
        bindOptions(select, this::showCopyOptions);
        ImageButton cut = toolButton(Icon.trash, "Cut", Mode.cut);
        bindOptions(cut, this::showCutOptions);
        menuBody.row();

        ImageButton paste = toolButton(Icon.paste, "Paste", Mode.paste);
        paste.setDisabled(() -> clipboard == null);
        bindOptions(paste, this::showPasteOptionsOnly);
        undoToggleBtn = menuBody.button(Icon.undo, Styles.squareTogglei, () -> {
            if(optionsPopup != null && "undo-list-popup".equals(optionsPopup.name)){
                undoListOpen = false;
                closeOptionsPopup();
                return;
            }
            undoListOpen = true;
            if(undoListOpen){
                closeOptionsPopup();
                showUndoList();
                optionsOwner = undoToggleBtn;
            }
        }).tooltip("Undo list").size(44f).get();
        undoToggleBtn.update(() -> undoToggleBtn.setChecked(undoListOpen));
        menuBody.button(Icon.rotate, Styles.flati, this::rotateClipboard).tooltip("Rotate Clipboard").disabled(b -> clipboard == null);
        menuBody.row();

        menuBody.button(Icon.flipX, Styles.flati, this::flipClipboardX).tooltip("Flip X").disabled(b -> clipboard == null);
        menuBody.button(Icon.flipY, Styles.flati, this::flipClipboardY).tooltip("Flip Y").disabled(b -> clipboard == null);
        menuBody.row();

        ImageButton drawBtn = toolButton(Icon.pencil, "Custom Draw", Mode.draw);
        bindOptions(drawBtn, this::showDrawOptions);
        ImageButton drawLineBtn = toolButton(Icon.line, "Custom Draw Line", Mode.drawLine);
        bindOptions(drawLineBtn, this::showDrawOptions);
        menuBody.row();

        ImageButton eraser = toolButton(Icon.eraser, "Custom Eraser", Mode.eraseOre);
        bindOptions(eraser, this::showEraserOptions);
        menuBody.stack(
            new Table(t -> t.add(menuBody.button(Icon.grid, Styles.squareTogglei, () -> advancedGrid = !advancedGrid)
                .tooltip("Advanced Grid")
                .update(b -> ((ImageButton)b).setChecked(advancedGrid)).get()).size(44f)),
            new Table(t -> {
                t.bottom().right();
                t.button(iconByName("extraeditor-grid-area", Icon.map), Styles.flati, () -> {
                    selectingGridArea = true;
                    setMode(Mode.none);
                }).size(14f).pad(1f).tooltip("Select Grid Area");
            })
        ).size(44f);
        menuBody.row();

        ImageButton replaceToggle = menuBody.button(Icon.refresh, Styles.squareTogglei, () -> {
            replaceMode = !replaceMode;
        }).tooltip("Replace mode").size(44f).get();
        replaceToggle.update(() -> {
            if((replaceFrom == null || replaceTo == null) && replaceMode) replaceMode = false;
            replaceToggle.setChecked(replaceMode);
        });
        fromPickButton = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
        fromPickButton.clicked(this::selectReplaceFrom);
        fromPickButton.resizeImage(8f * 4f);
        menuBody.add(fromPickButton).size(44f);
        fromPickButton.addListener(new Tooltip(t -> t.label(() -> replaceFrom == null ? "Set FROM from current right-panel selection" : replaceFrom.localizedName)));
        fromPickButton.update(() -> {
            setReplaceSlotIcon(fromPickButton, replaceFrom);
        });
        toPickButton = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
        toPickButton.clicked(this::selectReplaceTo);
        toPickButton.resizeImage(8f * 4f);
        menuBody.add(toPickButton).size(44f);
        toPickButton.addListener(new Tooltip(t -> t.label(() -> replaceTo == null ? "Set TO from current right-panel selection" : replaceTo.localizedName)));
        toPickButton.update(() -> {
            setReplaceSlotIcon(toPickButton, replaceTo);
        });
        menuBody.button(Icon.cancel, Styles.flati, () -> {
            replaceFrom = null;
            replaceTo = null;
            replaceMode = false;
        }).tooltip("Reset replace").size(44f);
        menuBody.row();

        menuBody.add("Brush Shape").colspan(2).center().growX().row();
        menuBody.table(t -> {
            t.defaults().size(34f).pad(3f);
            t.center();
            ImageButton b1 = t.button(iconByName("extraeditor-circle", Icon.add), Styles.squareTogglei, () -> brushShape = Shape.circle).tooltip("Circle").get();
            b1.update(() -> b1.setChecked(brushShape == Shape.circle));
            ImageButton b2 = t.button(iconByName("extraeditor-square", Icon.add), Styles.squareTogglei, () -> brushShape = Shape.square).tooltip("Square").get();
            b2.update(() -> b2.setChecked(brushShape == Shape.square));
            t.row();
            ImageButton b3 = t.button(iconByName("extraeditor-diamond", Icon.add), Styles.squareTogglei, () -> brushShape = Shape.diamond).tooltip("Diamond").get();
            b3.update(() -> b3.setChecked(brushShape == Shape.diamond));
            ImageButton b4 = t.button(iconByName("extraeditor-cross", Icon.add), Styles.squareTogglei, () -> brushShape = Shape.cross).tooltip("Cross").get();
            b4.update(() -> b4.setChecked(brushShape == Shape.cross));
        }).colspan(2).center().growX().padLeft(24f);
        menuBody.row();

        menuBody.add("Brush Size").colspan(2).center().row();
        menuBody.table(t -> {
            t.table(field -> {
                brushSizeField = field.field(getBrushSizeText(), text -> {
                    if(parseBrushSize(text)) updateBrushDisplay();
                }).width(74f).center().get();
                brushSizeField.setMaxLength(2);
                brushSizeField.setFilter(TextFieldFilter.digitsOnly);
            }).center().padBottom(4f);
            t.row();
            brushSlider = new Slider(1f, 20f, 1f, false);
            brushSlider.moved(v -> {
                brushRadius = (int)v;
                editor.brushSize = brushRadius;
                updateBrushDisplay();
            });
            brushSlider.setValue(brushRadius);
            t.add(brushSlider).width(112f).padLeft(68f).padRight(0f).center();
        }).colspan(2).padTop(4f).center().padLeft(12f);
        menuBody.row();

        menuBody.add().height(8f).colspan(2).row();
        menuBody.add("Grid Detail").colspan(2).center().row();
        menuBody.table(t -> {
            t.table(field -> {
                gridDetailField = field.field(String.valueOf(gridDetail), text -> {
                    try{
                        int val = Integer.parseInt(text);
                        int max = maxGridDetail();
                        if(max < 0) max = 0;
                        if(val >= 0 && val <= max){
                            gridDetail = val;
                            gridSlider.setValue(val);
                        }
                    }catch(NumberFormatException e){
                        // invalid input, ignore
                    }
                }).width(74f).center().get();
                gridDetailField.setMaxLength(2);
                gridDetailField.setFilter(TextFieldFilter.digitsOnly);
            }).center().padBottom(4f);
            t.row();
            gridSlider = new Slider(0f, 10f, 1f, false);
            gridSlider.moved(v -> {
                gridDetail = (int)v;
                if(gridDetailField != null){
                    int cursor = gridDetailField.getCursorPosition();
                    String txt = String.valueOf(gridDetail);
                    gridDetailField.setText(txt);
                    gridDetailField.setCursorPosition(Math.min(cursor, txt.length()));
                }
            });
            gridSlider.setValue(gridDetail);
            gridSlider.update(() -> {
                int max = maxGridDetail();
                if(max < 0) max = 0;
                gridSlider.setRange(0f, max);
                if(gridDetail > max){
                    gridDetail = max;
                    gridSlider.setValue(max);
                    if(gridDetailField != null){
                        int cursor = gridDetailField.getCursorPosition();
                        String txt = String.valueOf(max);
                        gridDetailField.setText(txt);
                        gridDetailField.setCursorPosition(Math.min(cursor, txt.length()));
                    }
                }
            });
            t.add(gridSlider).width(112f).padLeft(68f).padRight(0f).center();
        }).colspan(2).padTop(4f).center().padLeft(12f);
        menuBody.row();
        menuBody.add().height(24f).colspan(2);

        toolbar.pack();
    }

    private ImageButton toolButton(arc.scene.style.Drawable icon, String tip, Mode target){
        ImageButton button = menuBody.button(icon, Styles.squareTogglei, () -> {
            if(mode == target) setMode(Mode.none);
            else setMode(target);
        }).tooltip(tip).get();
        toolButtons.put(target, button);
        button.update(() -> button.setChecked(mode == target && !collapsed));
        return button;
    }

    private void bindOptions(ImageButton button, Runnable openOptions){
        final float[] downTime = {0f};
        final boolean[] holdFired = {false};
        button.update(() -> {
            if(mobile && downTime[0] > 0f && !holdFired[0] && button.isPressed()){
                if(arc.util.Time.time - downTime[0] >= 90f){
                    holdFired[0] = true;
                    if(optionsPopup != null && optionsOwner == button) closeOptionsPopup();
                    else{
                        closeOptionsPopup();
                        openOptions.run();
                        optionsOwner = button;
                    }
                }
            }
        });
        button.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode code){
                if(pointer == 0 && code == KeyCode.mouseRight){
                    if(optionsPopup != null && optionsOwner == button){
                        closeOptionsPopup();
                        event.cancel();
                        event.stop();
                        return true;
                    }
                    closeOptionsPopup();
                    openOptions.run();
                    optionsOwner = button;
                    event.cancel();
                    event.stop();
                    return true;
                }
                if(pointer == 0 && code == KeyCode.mouseLeft && mobile){
                    downTime[0] = arc.util.Time.time;
                    holdFired[0] = false;
                }
                return false;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode code){
                if(pointer == 0){
                    if(mobile && code == KeyCode.mouseLeft && !holdFired[0] && button.isChecked()){
                        if(optionsPopup != null && optionsOwner == button){
                            closeOptionsPopup();
                        }else{
                            closeOptionsPopup();
                            openOptions.run();
                            optionsOwner = button;
                        }
                    }
                    downTime[0] = 0f;
                }
            }
        });
    }

    private void showCopyOptions(){
        currentPopupKind = "copy";
        optionsPopup = buildCopyOptionsTable();
    }

    private void showPasteOptionsOnly(){
        currentPopupKind = "paste";
        optionsPopup = buildPasteOptionsTable();
    }

    private void showCutOptions(){
        currentPopupKind = "cut";
        optionsPopup = buildCopyAndPasteOptionsTable();
    }

    private void showEraserOptions(){
        currentPopupKind = "eraser";
        optionsPopup = buildEraserOptionsTable();
    }

    private Table buildCopyOptionsTable(){
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.check("Copy floor", copyFloor, v -> copyFloor = v).row();
        pop.check("Copy ore", copyOre, v -> copyOre = v).row();
        pop.check("Copy blocks", copyBlock, v -> copyBlock = v).row();
        return showOptionsPopup(pop);
    }

    private Table buildPasteOptionsTable(){
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.check("Override blocks", overrideBlock, v -> overrideBlock = v).row();
        pop.check("Override ores", overrideOre, v -> overrideOre = v).row();
        pop.check("Smart pasting", smartPasting, v -> smartPasting = v).row();
        pop.check("Use editor team", pasteUseEditorTeam, v -> pasteUseEditorTeam = v).row();
        return showOptionsPopup(pop);
    }

    private Table buildDrawOptionsTable(){
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.check("Override blocks", overrideBlock, v -> overrideBlock = v).row();
        pop.check("Override ores", overrideOre, v -> overrideOre = v).row();
        pop.pack();
        return showOptionsPopup(pop);
    }

    private void showDrawOptions(){
        currentPopupKind = "draw";
        optionsPopup = buildDrawOptionsTable();
    }

    private void showUndoList(){
        if(!undoListOpen) return;
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.name = "undo-list-popup";
        currentPopupKind = "undo";
        undoListOpen = true;

        // obtain underlying OperationStack via reflection
        Object stackObj = Reflect.get(mindustry.editor.MapEditor.class, editor, "stack");
        if(stackObj == null){
            pop.add("No undo stack available");
            showOptionsPopup(pop);
            return;
        }

        @SuppressWarnings("unchecked")
        arc.struct.Seq<mindustry.editor.DrawOperation> seq = (arc.struct.Seq<mindustry.editor.DrawOperation>)Reflect.get(mindustry.editor.OperationStack.class, stackObj, "stack");

        Table header = new Table();
        header.add("Actions").left();
        header.add().growX();
        header.button(Icon.refresh, Styles.clearNonei, () -> {
            if(undoListOpen) showUndoList();
        }).size(20f).tooltip("Update");
        pop.add(header).growX().row();

        Table list = new Table();
        if(seq == null || seq.isEmpty()){
            list.add("No actions");
        }else{
            for(int pass = 0; pass < 2; pass++){
                boolean wantReverted = pass == 0;
                list.add(wantReverted ? "Reverted" : "Actions").left().padTop(pass == 0 ? 2f : 6f).padBottom(4f).row();
                for(int i = seq.size - 1; i >= 0; i--){
                mindustry.editor.DrawOperation op = seq.get(i);
                boolean isReverted = revertedOperations.contains(op);
                if(isReverted != wantReverted) continue;
                Table row = new Table();
                Table entry = new Table();

                // icon (heuristic): determine action type from first operation
                TextureRegionDrawable iconDrawable = new TextureRegionDrawable(Icon.copy.getRegion());
                String toolName = "Action";
                try{
                    Object arrayObj = Reflect.get(mindustry.editor.DrawOperation.class, op, "array");
                    if(arrayObj != null){
                        long first = 0L;
                        try{
                            Object items = Reflect.get(arrayObj.getClass(), arrayObj, "items");
                            int asize = (int)Reflect.get(arrayObj.getClass(), arrayObj, "size");
                            if(items != null && asize > 0){
                                long[] arr = (long[])items;
                                first = arr[0];
                            }
                        }catch(Exception ex){}
                        
                        int type = 0, value = 0;
                        try{ type = (int)Reflect.invoke(mindustry.gen.TileOp.class, null, "type", new Object[]{first}); }catch(Exception ex){}
                        try{ value = (int)Reflect.invoke(mindustry.gen.TileOp.class, null, "value", new Object[]{first}); }catch(Exception ex){}
                        
                        if(type == 1){
                            if(value > 0){ 
                                iconDrawable = new TextureRegionDrawable(Icon.pencil.getRegion()); 
                                toolName = "Draw Blocks"; 
                            }else{ 
                                iconDrawable = new TextureRegionDrawable(Icon.eraser.getRegion()); 
                                toolName = "Erase Blocks"; 
                            }
                        }else if(type == 4){
                            if(value > 0){ 
                                iconDrawable = new TextureRegionDrawable(Icon.pencil.getRegion()); 
                                toolName = "Place Overlay"; 
                            }else{ 
                                iconDrawable = new TextureRegionDrawable(Icon.eraser.getRegion()); 
                                toolName = "Remove Overlay"; 
                            }
                        }else if(type == 0){
                            iconDrawable = new TextureRegionDrawable(Icon.image.getRegion());
                            toolName = "Floor Change";
                        }else{
                            toolName = "Edit";
                        }
                    }
                }catch(Exception e){
                    // ignore heuristics
                }

                row.add(new arc.scene.ui.Image(iconDrawable)).size(20f).padRight(6f).left();

                // main label
                row.add("Action " + (i + 1)).left().padRight(6f);

                // tiles affected
                row.add("Tiles: " + op.size()).left().padRight(6f);

                // time
                Long ts = opTimestamps.get(op);
                String timeStr = ts == null ? "?" : new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(ts));
                row.add(timeStr).left().padRight(6f);

                row.add().growX();

                // expand/collapse preview button
                ImageButton exp = row.button(Icon.downOpen, Styles.flati, () -> {}).size(34f).get();
                final Table[] previewHolder = new Table[1];
                exp.clicked(() -> {
                    boolean expanded = previewHolder[0] != null && previewHolder[0].parent != null;
                    if(expanded){
                        if(previewHolder[0] != null) previewHolder[0].remove();
                        exp.getStyle().imageUp = Icon.downOpen;
                    }else{
                        Table preview = new Table(Tex.button);
                        try{
                            UndoPreviewData data = decodeUndoPreviewData(op);
                            final float[] cell = {12f};
                            final Table[] gridHolder = {buildUndoPreviewGrid(data, cell[0])};
                            final ScrollPane pane = new ScrollPane(gridHolder[0]);
                            pane.setFadeScrollBars(true);
                            pane.setScrollingDisabled(false, false);
                            try{
                                Reflect.set(pane.getStyle().getClass(), pane.getStyle(), "hScroll", Tex.clear);
                                Reflect.set(pane.getStyle().getClass(), pane.getStyle(), "hScrollKnob", Tex.clear);
                                Reflect.set(pane.getStyle().getClass(), pane.getStyle(), "vScroll", Tex.clear);
                                Reflect.set(pane.getStyle().getClass(), pane.getStyle(), "vScrollKnob", Tex.clear);
                            }catch(Throwable ignored){}
                            pane.addCaptureListener(new InputListener(){
                                @Override
                                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                                    Element hover = Core.scene.getHoverElement();
                                    if(hover == null || !isDescendant(hover, pane)) return false;
                                    cell[0] = Mathf.clamp(cell[0] - amountY, 4f, 28f);
                                    float sx = pane.getScrollX(), sy = pane.getScrollY();
                                    Table next = buildUndoPreviewGrid(data, cell[0]);
                                    pane.setWidget(next);
                                    pane.setScrollX(sx);
                                    pane.setScrollY(sy);
                                    event.cancel();
                                    event.stop();
                                    return true;
                                }
                            });
                            final float[] pinchLast = {-1f};
                            pane.update(() -> {
                                if(!mobile){
                                    pinchLast[0] = -1f;
                                    return;
                                }
                                Element hover = Core.scene.getHoverElement();
                                if(hover == null || !isDescendant(hover, pane)){
                                    pinchLast[0] = -1f;
                                    return;
                                }
                                if(Core.input.isTouched(0) && Core.input.isTouched(1)){
                                    float x0 = Core.input.mouseX(0), y0 = Core.input.mouseY(0);
                                    float x1 = Core.input.mouseX(1), y1 = Core.input.mouseY(1);
                                    float dist = Mathf.dst(x0, y0, x1, y1);
                                    if(pinchLast[0] > 0f){
                                        float delta = dist - pinchLast[0];
                                        cell[0] = Mathf.clamp(cell[0] + delta * 0.03f, 4f, 28f);
                                        float sx = pane.getScrollX(), sy = pane.getScrollY();
                                        Table next = buildUndoPreviewGrid(data, cell[0]);
                                        pane.setWidget(next);
                                        pane.setScrollX(sx);
                                        pane.setScrollY(sy);
                                    }
                                    pinchLast[0] = dist;
                                }else{
                                    pinchLast[0] = -1f;
                                }
                            });
                            preview.add(pane).size(360f, 220f);
                        }catch(Throwable ex){
                            preview.add("[red]Preview unavailable");
                        }
                        previewHolder[0] = preview;
                        try{
                            // insert preview directly under this entry container
                            entry.add(preview).growX().pad(4f).row();
                        }catch(Exception ex){
                            list.add(preview).growX().pad(4f).row();
                        }
                        exp.getStyle().imageUp = Icon.upOpen;
                    }
                });

                // revert/redo button
                row.button(isReverted ? Icon.redo : Icon.cancel, Styles.flati, () -> {
                    try{
                        if(revertedOperations.contains(op)){
                            op.redo();
                            revertedOperations.remove(op);
                        }else{
                            op.undo();
                            revertedOperations.add(op);
                        }
                        editor.flushOp();
                        ui.editor.resetSaved();
                        if(undoListOpen) showUndoList();
                    }catch(Exception e){ }
                }).size(34f).padLeft(6f).tooltip(isReverted ? "Redo" : "Revert");

                entry.add(row).growX().pad(2f).row();
                list.add(entry).growX().row();
            }
            }
        }

        ScrollPane sc = new ScrollPane(list);
        sc.setFadeScrollBars(false);
        // size the undo popup wider and height based on entries (clamped)
        pop.add(sc).size(620f, 700f);
        optionsPopup = showOptionsPopup(pop);
    }

    private static class UndoPreviewData{
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        final Set<Long> coords = new HashSet<>();
        final Set<Long> deleted = new HashSet<>();
        final java.util.Map<Long, TextureRegion> oldIcon = new java.util.HashMap<>();
        final java.util.Map<Long, TextureRegion> replacementIcon = new java.util.HashMap<>();
    }

    private static class UndoCellChange{
        int type = -1; // 0=floor,1=block,4=overlay
        TextureRegion oldIcon;
        TextureRegion newIcon;
        boolean deleted;
        boolean replaced;
    }

    private UndoPreviewData decodeUndoPreviewData(mindustry.editor.DrawOperation op){
        LongSeq opArray = Reflect.get(mindustry.editor.DrawOperation.class, op, "array");
        if(opArray == null || opArray.size <= 0) throw new RuntimeException("empty operation");
        UndoPreviewData out = new UndoPreviewData();
        java.util.Map<Long, UndoCellChange> byCell = new java.util.HashMap<>();
        for(int i = 0; i < opArray.size; i++){
            long l = opArray.get(i);
            int tx = TileOp.x(l), ty = TileOp.y(l), type = TileOp.type(l), oldValue = TileOp.value(l);
            long key = ((long)tx << 32) | (ty & 0xFFFFFFFFL);
            out.coords.add(key);
            out.minx = Math.min(out.minx, tx); out.miny = Math.min(out.miny, ty);
            out.maxx = Math.max(out.maxx, tx); out.maxy = Math.max(out.maxy, ty);
            Tile t = editor.tile(tx, ty);
            UndoCellChange cell = byCell.get(key);
            if(cell == null){
                cell = new UndoCellChange();
                byCell.put(key, cell);
            }
            if(type == 1){
                cell.type = 1;
                if(oldValue > 0){
                    Block old = content.block(oldValue);
                    if(old != null) cell.oldIcon = old.fullIcon != null ? old.fullIcon : old.uiIcon;
                }else{
                    cell.oldIcon = null;
                }
                Block nb = t == null ? Blocks.air : t.block();
                cell.newIcon = (nb != null && nb != Blocks.air) ? (nb.fullIcon != null ? nb.fullIcon : nb.uiIcon) : null;
                cell.deleted = cell.oldIcon != null && cell.newIcon == null;
                cell.replaced = cell.oldIcon != null && cell.newIcon != null && cell.oldIcon != cell.newIcon;
            }else if(type == 4){
                cell.type = 4;
                if(oldValue > 0){
                    Block old = content.block(oldValue);
                    if(old != null) cell.oldIcon = old.uiIcon;
                }else{
                    cell.oldIcon = null;
                }
                Block no = t == null ? Blocks.air : t.overlay();
                cell.newIcon = (no != null && no != Blocks.air) ? no.uiIcon : null;
                cell.deleted = cell.oldIcon != null && cell.newIcon == null;
                cell.replaced = cell.oldIcon != null && cell.newIcon != null && cell.oldIcon != cell.newIcon;
            }else if(type == 0){
                cell.type = 0;
                if(oldValue > 0){
                    Block old = content.block(oldValue);
                    if(old instanceof Floor) cell.oldIcon = old.uiIcon;
                }
                Floor nf = t == null ? null : t.floor();
                cell.newIcon = nf == null ? null : nf.uiIcon;
                cell.deleted = false;
                cell.replaced = cell.oldIcon != null && cell.newIcon != null && cell.oldIcon != cell.newIcon;
            }
        }
        for(java.util.Map.Entry<Long, UndoCellChange> e : byCell.entrySet()){
            long key = e.getKey();
            UndoCellChange c = e.getValue();
            if(c.oldIcon != null) out.oldIcon.put(key, c.oldIcon);
            if(c.deleted || c.replaced) out.deleted.add(key);
            if(c.replaced && c.newIcon != null) out.replacementIcon.put(key, c.newIcon);
        }
        return out;
    }

    private Table buildUndoPreviewGrid(UndoPreviewData data, float cellSize){
        Table preview = new Table(Tex.button);
        preview.defaults().size(cellSize).pad(0f);
        if(data.coords.isEmpty()){
            preview.add("No tiles in action");
            return preview;
        }
        int w = Math.min(64, data.maxx - data.minx + 1);
        int h = Math.min(64, data.maxy - data.miny + 1);
        for(int yy = 0; yy < h; yy++){
            for(int xx = 0; xx < w; xx++){
                int tx = data.minx + xx, ty = data.maxy - yy;
                long key = ((long)tx << 32) | (ty & 0xFFFFFFFFL);
                if(data.coords.contains(key)){
                    Tile t = editor.tile(tx, ty);
                    TextureRegion region = null;
                    if(data.deleted.contains(key) && data.oldIcon.containsKey(key)) region = data.oldIcon.get(key);
                    else if(t != null && t.block() != Blocks.air && t.block().fullIcon != null) region = t.block().fullIcon;
                    else if(t != null && t.overlay() != Blocks.air && t.overlay().uiIcon != null) region = t.overlay().uiIcon;
                    else if(t != null && t.floor() != null) region = t.floor().uiIcon;
                    if(region != null && region.texture != null){
                        Table cell = new Table();
                        TextureRegion replacement = data.replacementIcon.get(key);
                        cell.stack(
                            new Image(new TextureRegionDrawable(region)),
                            new arc.scene.Element(){
                                @Override
                                public void draw(){
                                    if(!data.deleted.contains(key)) return;
                                    Draw.color(Pal.remove, 0.55f);
                                    Lines.stroke(1.5f);
                                    Lines.line(x, y, x + width, y + height);
                                    Lines.line(x, y + height, x + width, y);
                                    Draw.reset();
                                }
                            },
                            new Image(new TextureRegionDrawable(replacement == null ? region : replacement)){{
                                visible(() -> data.deleted.contains(key) && replacement != null);
                                setScaling(arc.util.Scaling.fit);
                            }}
                        );
                        if(replacement != null) cell.getCells().get(cell.getCells().size - 1).size(cellSize * 0.5f);
                        preview.add(cell).size(cellSize).pad(0f);
                    }else{
                        preview.image().color(data.deleted.contains(key) ? Pal.remove : Color.gray).size(cellSize).pad(0f);
                    }
                }else{
                    preview.image().color(Color.clear).size(cellSize).pad(0f);
                }
            }
            preview.row();
        }
        return preview;
    }

    private Table buildCopyAndPasteOptionsTable(){
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.check("Copy floor", copyFloor, v -> copyFloor = v).row();
        pop.check("Copy ore", copyOre, v -> copyOre = v).row();
        pop.check("Copy blocks", copyBlock, v -> copyBlock = v).row();
        pop.image().height(2f).growX().pad(2f).row();
        pop.check("Override blocks", overrideBlock, v -> overrideBlock = v).row();
        pop.check("Override ores", overrideOre, v -> overrideOre = v).row();
        pop.check("Smart pasting", smartPasting, v -> smartPasting = v).row();
        pop.check("Use editor team", pasteUseEditorTeam, v -> pasteUseEditorTeam = v).row();
        return showOptionsPopup(pop);
    }

    private Table showOptionsPopup(Table pop){
        if(optionsPopup != null && optionsPopup != pop){
            optionsPopup.remove();
        }
        pop.pack();
        ui.editor.addChild(pop);
        Vec2 anchor = toolbar.localToStageCoordinates(new Vec2(toolbar.getWidth(), toolbar.getHeight()));
        Vec2 local = ui.editor.stageToLocalCoordinates(anchor);
        pop.setPosition(local.x + 6f, local.y - pop.getPrefHeight());
        pop.toFront();
        optionsPopup = pop;
        return pop;
    }

    private Table buildEraserOptionsTable(){
        Table pop = new Table(Tex.button);
        pop.defaults().left().pad(3f);
        pop.check("Erase blocks", eraseBlocks, v -> eraseBlocks = v).row();
        pop.check("Erase ores", eraseOres, v -> eraseOres = v).row();
        pop.check("Only selected team", eraseTeamOnly, v -> eraseTeamOnly = v).row();
        pop.pack();
        ui.editor.addChild(pop);
        Vec2 anchor = toolbar.localToStageCoordinates(new Vec2(toolbar.getWidth(), toolbar.getHeight()));
        Vec2 local = ui.editor.stageToLocalCoordinates(anchor);
        pop.setPosition(local.x + 6f, local.y - pop.getPrefHeight());
        pop.toFront();
        return pop;
    }

    private void closeOptionsPopup(){
        if(optionsPopup != null){
            if("undo-list-popup".equals(optionsPopup.name)){
                undoListOpen = false;
            }
            optionsPopup.remove();
            optionsPopup = null;
        }
        optionsOwner = null;
        if(!restoringPopup){
            currentPopupKind = null;
        }
    }

    private void stashPopupState(){
        stashedPopupKind = currentPopupKind;
    }

    private void restoreStashedPopup(){
        if(stashedPopupKind == null) return;
        restoringPopup = true;
        try{
            switch(stashedPopupKind){
                case "undo":
                    undoListOpen = true;
                    showUndoList();
                    break;
                case "copy":
                    showCopyOptions();
                    break;
                case "paste":
                    showPasteOptionsOnly();
                    break;
                case "cut":
                    showCutOptions();
                    break;
                case "draw":
                    showDrawOptions();
                    break;
                case "eraser":
                    showEraserOptions();
                    break;
                default:
                    break;
            }
        }finally{
            stashedPopupKind = null;
            restoringPopup = false;
        }
    }

    private void rebuildToolbar(){
        if(toolbar != null){
            toolbar.clearChildren();
        }
        buildToolbar();
    }

    private void positionUi(){
        if(attachedView == null || toolbar == null || overlay == null) return;
        toolbar.toFront();
        overlay.setBounds(0f, 0f, ui.editor.getWidth(), ui.editor.getHeight());
        Vec2 stage = attachedView.localToStageCoordinates(new Vec2(0f, attachedView.getHeight()));
        Vec2 local = ui.editor.stageToLocalCoordinates(stage);
        toolbar.setPosition(local.x + 8f, local.y - toolbar.getPrefHeight() - 8f);
        if(optionsPopup != null) optionsPopup.toFront();
        if(mobilePasteBar != null) mobilePasteBar.toFront();
        if(mobilePasteBar != null){
            mobilePasteBar.setPosition((ui.editor.getWidth() - mobilePasteBar.getPrefWidth()) / 2f, 10f);
        }
    }

    private void syncModeWithVanillaTool(){
        if(attachedView == null) return;
        EditorTool current = attachedView.getTool();
        if(mode != Mode.none && current != null && current != EditorTool.pick){
            setMode(Mode.none);
            current = attachedView.getTool();
        }
        if(mode == Mode.none){
            lastVanillaTool = current;
        }
    }

    private void setMode(Mode next){
        mode = next;
        selecting = false;
        drawingLine = false;
        mobilePasteDragging = false;
        closeOptionsPopup();
        if(next == Mode.paste && mobile){
            ensureMobilePasteOrigin();
            showMobilePasteBar();
        }else{
            hideMobilePasteBar();
        }
        refreshToolChecks();
        if(attachedView != null && next != Mode.none){
            attachedView.setTool(EditorTool.pick);
            Core.scene.setScrollFocus(attachedView);
        }else if(attachedView != null && lastVanillaTool != null){
            attachedView.setTool(lastVanillaTool);
        }
    }

    private void refreshToolChecks(){
        toolButtons.each((m, b) -> b.setChecked(mode == m && !collapsed));
    }

    private void copySelection(boolean cut){
        int minX = Math.min(selectStartX, selectEndX);
        int minY = Math.min(selectStartY, selectEndY);
        int maxX = Math.max(selectStartX, selectEndX);
        int maxY = Math.max(selectStartY, selectEndY);
        minX = Mathf.clamp(minX, 0, editor.width() - 1);
        minY = Mathf.clamp(minY, 0, editor.height() - 1);
        maxX = Mathf.clamp(maxX, 0, editor.width() - 1);
        maxY = Mathf.clamp(maxY, 0, editor.height() - 1);
        if(minX > maxX || minY > maxY) return;
        Clip out = new Clip(maxX - minX + 1, maxY - minY + 1);
        out.anchorX = selectEndX - minX;
        out.anchorY = selectEndY - minY;

        for(int x = 0; x < out.width; x++){
            for(int y = 0; y < out.height; y++){
                Tile tile = editor.tile(minX + x, minY + y);
                out.set(x, y, TileData.get(tile, copyFloor, copyOre, copyBlock));
            }
        }

        if(cut){
            for(int x = 0; x < out.width; x++){
                for(int y = 0; y < out.height; y++){
                    Tile tile = editor.tile(minX + x, minY + y);
                    if(copyBlock) tile.remove();
                    if(copyOre && tile.overlay() instanceof OreBlock) tile.clearOverlay();
                }
            }
            editor.flushOp();
            ui.editor.resetSaved();
        }

        clipboard = out;
        mobilePasteOriginSet = false;
    }

    private boolean pasteFits(int x, int y){
        return clipboard != null && x >= 0 && y >= 0 && x + clipboard.width <= editor.width() && y + clipboard.height <= editor.height();
    }

    private void pasteAt(int x, int y){
        if(clipboard == null) return;

        int total = clipboard.width * clipboard.height;
        int threshold = 10000; // cells; above this we'll do a batched paste to avoid freezing
        boolean showPasteProgress = total > 5000;
        if(total <= threshold){
            // small paste — do it synchronously
            if(copyBlock && overrideBlock){
                for(int cx = 0; cx < clipboard.width; cx++){
                    for(int cy = 0; cy < clipboard.height; cy++){
                        int tx = x + cx, ty = y + cy;
                        if(!inBounds(tx, ty)) continue;
                        TileData data = clipboard.get(cx, cy);
                        if(data.center && data.block != Blocks.air){
                            editor.tile(tx, ty).remove();
                        }
                    }
                }
            }

            for(int cx = 0; cx < clipboard.width; cx++){
                for(int cy = 0; cy < clipboard.height; cy++){
                    int tx = x + cx, ty = y + cy;
                    if(!inBounds(tx, ty)) continue;
                    TileData data = clipboard.get(cx, cy);
                    Tile tile = editor.tile(tx, ty);
                    if(data.floor != null){
                        tile.setFloor(data.floor);
                        tile.data = data.data;
                        tile.floorData = data.floorData;
                        tile.overlayData = data.overlayData;
                        tile.extraData = data.extraData;
                    }
                    if(data.overlay != Blocks.air){
                        if(overrideOre || !(tile.overlay() instanceof OreBlock)){
                            tile.setOverlay(data.overlay);
                        }
                    }
                }
            }

            for(int cx = 0; cx < clipboard.width; cx++){
                for(int cy = 0; cy < clipboard.height; cy++){
                    int tx = x + cx, ty = y + cy;
                    if(!inBounds(tx, ty)) continue;
                    TileData data = clipboard.get(cx, cy);
                    if(data.center && data.block != Blocks.air){
                        Tile tile = editor.tile(tx, ty);
                        if(overrideBlock || tile.block() == Blocks.air){
                            tile.setBlock(data.block, pasteUseEditorTeam ? editor.drawTeam : data.team, data.rotation);
                        }
                    }
                }
            }

            editor.flushOp();
            ui.editor.resetSaved();
            return;
        }

        // Large paste: do in batches to avoid freezing
        final int W = clipboard.width, H = clipboard.height;
        final int[] phase = {0}; // 0 = remove centers, 1 = set floors+overlays, 2 = set blocks
        final int[] idx = {0};
        final int chunk = 2000; // cells per tick
        // start progress
        if(showPasteProgress){
            pastingInProgress = true;
            pasteProgress = 0f;
        }
        final arc.util.Timer.Task[] taskRef = new arc.util.Timer.Task[1];
        taskRef[0] = arc.util.Timer.schedule(() -> {
            int processed = 0;
            while(processed < chunk && phase[0] < 3){
                int i = idx[0];
                int cx = i % W;
                int cy = i / W;
                int tx = x + cx, ty = y + cy;
                if(phase[0] == 0){
                    if(copyBlock && overrideBlock){
                        if(inBounds(tx, ty)){
                            TileData data = clipboard.get(cx, cy);
                            if(data.center && data.block != Blocks.air){
                                editor.tile(tx, ty).remove();
                            }
                        }
                    }
                }else if(phase[0] == 1){
                    if(inBounds(tx, ty)){
                        TileData data = clipboard.get(cx, cy);
                        Tile tile = editor.tile(tx, ty);
                        if(data.floor != null){
                            tile.setFloor(data.floor);
                            tile.data = data.data;
                            tile.floorData = data.floorData;
                            tile.overlayData = data.overlayData;
                            tile.extraData = data.extraData;
                        }
                        if(data.overlay != Blocks.air){
                            if(overrideOre || !(tile.overlay() instanceof OreBlock)){
                                tile.setOverlay(data.overlay);
                            }
                        }
                    }
                }else{
                    if(inBounds(tx, ty)){
                        TileData data = clipboard.get(cx, cy);
                        if(data.center && data.block != Blocks.air){
                            Tile tile = editor.tile(tx, ty);
                            if(overrideBlock || tile.block() == Blocks.air){
                                tile.setBlock(data.block, pasteUseEditorTeam ? editor.drawTeam : data.team, data.rotation);
                            }
                        }
                    }
                }

                idx[0]++;
                if(idx[0] >= W * H){
                    idx[0] = 0;
                    phase[0]++;
                }
                processed++;
            }

            // update progress
            {
                int totalWork = W * H * 3;
                int currentWork = Math.min(totalWork, phase[0] * W * H + idx[0]);
                if(showPasteProgress){
                    pasteProgress = Math.min(1f, currentWork / (float)totalWork);
                }
            }

            if(phase[0] >= 3){
                if(showPasteProgress){
                    pasteProgress = 1f;
                    pastingInProgress = false;
                }
                taskRef[0].cancel();
                editor.flushOp();
                ui.editor.resetSaved();
            }
        }, 0f, 0.016f);
    }

    private void rotateClipboard(){
        if(clipboard == null) return;
        Clip next = new Clip(clipboard.height, clipboard.width);
        for(int x = 0; x < clipboard.width; x++){
            for(int y = 0; y < clipboard.height; y++){
                TileData data = clipboard.get(x, y).copy();
                if(data.block.rotate) data.rotation = Mathf.mod(data.rotation + 1, 4);
                next.set(clipboard.height - 1 - y, x, data);
            }
        }
        next.anchorX = clipboard.height - 1 - clipboard.anchorY;
        next.anchorY = clipboard.anchorX;
        clipboard = next;
    }

    private void flipClipboardX(){
        if(clipboard == null) return;
        Clip next = new Clip(clipboard.width, clipboard.height);
        for(int x = 0; x < clipboard.width; x++){
            for(int y = 0; y < clipboard.height; y++){
                TileData data = clipboard.get(x, y).copy();
                if(data.block.rotate){
                    if(data.rotation == 0) data.rotation = 2;
                    else if(data.rotation == 2) data.rotation = 0;
                }
                next.set(clipboard.width - 1 - x, y, data);
            }
        }
        next.anchorX = clipboard.width - 1 - clipboard.anchorX;
        next.anchorY = clipboard.anchorY;
        clipboard = next;
    }

    private void flipClipboardY(){
        if(clipboard == null) return;
        Clip next = new Clip(clipboard.width, clipboard.height);
        for(int x = 0; x < clipboard.width; x++){
            for(int y = 0; y < clipboard.height; y++){
                TileData data = clipboard.get(x, y).copy();
                if(data.block.rotate){
                    if(data.rotation == 1) data.rotation = 3;
                    else if(data.rotation == 3) data.rotation = 1;
                }
                next.set(x, clipboard.height - 1 - y, data);
            }
        }
        next.anchorX = clipboard.anchorX;
        next.anchorY = clipboard.height - 1 - clipboard.anchorY;
        clipboard = next;
    }

    private String getBrushSizeText(){
        return String.valueOf(brushRadius);
    }

    private boolean parseBrushSize(String text){
        text = text.trim();
        if(text.isEmpty()) return false;
        try{
            int val = Integer.parseInt(text);
            if(val < 1) return false;
            brushRadius = val;
            editor.brushSize = brushRadius;
            if(brushSlider != null){
                // slider constructed with max 20f; clamp slider to that maximum
                brushSlider.setValue(Math.min(val, 20));
            }
            return true;
        }catch(NumberFormatException e){
            return false;
        }
    }

    private void updateBrushDisplay(){
        if(brushSizeField != null){
            int cursor = brushSizeField.getCursorPosition();
            String txt = getBrushSizeText();
            brushSizeField.setText(txt);
            brushSizeField.setCursorPosition(Math.min(cursor, txt.length()));
        }
    }

    private void eraseOreAt(int x, int y){
        applyBrushCells(x, y, brushRadius, tile -> {
            if(eraseOres && tile.overlay() instanceof OreBlock){
                tile.clearOverlay();
            }
            if(eraseBlocks && tile.block() != Blocks.air){
                if(eraseTeamOnly){
                    if(tile.block().synthetic() && tile.team() == editor.drawTeam){
                        tile.remove();
                    }
                }else{
                    tile.remove();
                }
            }
        });
    }

    private void applyBrushDrawAt(int x, int y){
        Block draw = activeDrawBlock();
        if(draw != null && draw.isMultiblock()){
            Tile tile = editor.tile(x, y);
            if(tile != null && (!replaceMode || (replaceFrom != null && replaceTo != null && matchesReplaceSource(tile, replaceFrom)))){
                // respect override settings for multiblocks
                if(draw.isOverlay()){
                    if(overrideOre || tile.overlay() == Blocks.air) placeSingleBlockAt(x, y, draw);
                }else{
                    if(overrideBlock || tile.block() == Blocks.air) placeSingleBlockAt(x, y, draw);
                }
            }
            return;
        }

        applyBrushCells(x, y, brushRadius, tile -> {
            Block chosen;
            if(replaceMode){
                if(replaceFrom == null || replaceTo == null || !matchesReplaceSource(tile, replaceFrom)) return;
                chosen = replaceTo;
            }else{
                chosen = editor.drawBlock;
            }
            if(chosen == null) return;
            if(chosen.isOverlay()){
                if(!overrideOre && tile.overlay() != Blocks.air) return;
            }else if(!chosen.isFloor()){
                if(!overrideBlock && tile.block() != Blocks.air) return;
            }
            placeBlockFromPalette(tile, chosen);
        });
    }

    private Block activeDrawBlock(){
        return replaceMode && replaceTo != null ? replaceTo : editor.drawBlock;
    }

    private int activeDrawSpan(){
        Block draw = activeDrawBlock();
        if(draw != null && draw.isMultiblock()) return Math.max(1, draw.size);
        return Math.max(1, brushRadius);
    }

    private boolean matchesReplaceSource(Tile tile, Block source){
        if(source == null) return false;
        if(source.isOverlay()) return tile.overlay() == source;
        if(source.isFloor()) return tile.floor() == source;
        return tile.block() == source;
    }

    private void placeBlockFromPalette(Tile tile, Block draw){
        if(draw == null) return;
        if(draw.isOverlay()){
            tile.setOverlay(draw);
        }else if(draw.isFloor() && draw != Blocks.air){
            tile.setFloor(draw.asFloor());
        }else{
            tile.setBlock(draw, editor.drawTeam, editor.rotation);
        }
    }

    private void placeSingleBlockAt(int x, int y, Block draw){
        Block previous = editor.drawBlock;
        editor.drawBlock = draw;
        editor.drawBlocks(x, y);
        editor.drawBlock = previous;
    }

    private void selectReplaceFrom(){
        Block selected = editor.drawBlock == null ? Blocks.air : editor.drawBlock;
        if(replaceTo != null && !compatibleReplaceType(selected, replaceTo)) replaceTo = null;
        replaceFrom = selected;
        if(replaceTo == null) replaceMode = false;
        setReplaceSlotIcon(fromPickButton, replaceFrom);
        if(toPickButton != null) setReplaceSlotIcon(toPickButton, replaceTo);
    }

    private void selectReplaceTo(){
        Block selected = editor.drawBlock == null ? Blocks.air : editor.drawBlock;
        if(replaceFrom != null && !compatibleReplaceType(replaceFrom, selected)) replaceFrom = null;
        replaceTo = selected;
        if(replaceFrom == null) replaceMode = false;
        if(fromPickButton != null) setReplaceSlotIcon(fromPickButton, replaceFrom);
        setReplaceSlotIcon(toPickButton, replaceTo);
    }

    private boolean compatibleReplaceType(Block a, Block b){
        return replaceType(a) == replaceType(b);
    }

    private int replaceType(Block b){
        if(b == null) return -1;
        if(b.isOverlay()) return b instanceof OreBlock ? 2 : -2;
        if(b.isFloor()) return 1;
        return 0;
    }

    private TextureRegionDrawable blockDrawable(Block block){
        if(block == null){
            return new TextureRegionDrawable(Icon.add.getRegion());
        }
        TextureRegion ui = block.uiIcon;
        if(ui != null){
            return new TextureRegionDrawable(ui);
        }
        TextureRegion full = block.fullIcon;
        if(full != null){
            return new TextureRegionDrawable(full);
        }
        TextureRegion named = Core.atlas.find(block.name + "-ui");
        if(named != null && Core.atlas.isFound(named)){
            return new TextureRegionDrawable(named);
        }
        named = Core.atlas.find(block.name + "-full");
        if(named != null && Core.atlas.isFound(named)){
            return new TextureRegionDrawable(named);
        }
        named = Core.atlas.find(block.name);
        if(named != null && Core.atlas.isFound(named)){
            return new TextureRegionDrawable(named);
        }
        return new TextureRegionDrawable(Icon.add.getRegion());
    }

    private void setReplaceSlotIcon(ImageButton button, Block block){
        if(button == null) return;
        Drawable drawable = block == null ? Icon.add : blockDrawable(block);
        button.getStyle().imageUp = drawable;
        button.getStyle().imageDown = drawable;
        button.getStyle().imageOver = drawable;
        button.getStyle().imageChecked = drawable;
        button.getImage().setDrawable(drawable);
        button.resizeImage(8f * 4f);
    }

    private int maxGridDetail(){
        int w = editor.width(), h = editor.height();
        if(gridArea != null){
            w = Math.max(1, gridArea.width);
            h = Math.max(1, gridArea.height);
        }
        int min = Math.max(1, Math.min(w, h));
        int steps = 0;
        while((1 << steps) < min && steps < 30) steps++;
        return steps;
    }

    private Drawable iconByName(String name, Drawable fallback){
        TextureRegion region = Core.atlas.find(name);
        if(region != null && Core.atlas.isFound(region)) return new TextureRegionDrawable(region);
        region = Core.atlas.find("extra-editor-" + name);
        if(region != null && Core.atlas.isFound(region)) return new TextureRegionDrawable(region);
        region = Core.atlas.find("extraeditor-" + name);
        if(region != null && Core.atlas.isFound(region)) return new TextureRegionDrawable(region);
        return fallback;
    }

    private void applyLineDraw(){
        Seq<Point2> points = new Seq<>();
        int dx = Math.abs(lineEndX - lineStartX);
        int sx = lineStartX < lineEndX ? 1 : -1;
        int dy = -Math.abs(lineEndY - lineStartY);
        int sy = lineStartY < lineEndY ? 1 : -1;
        int err = dx + dy;
        int x = lineStartX, y = lineStartY;
        while(true){
            points.add(new Point2(x, y));
            if(x == lineEndX && y == lineEndY) break;
            int e2 = 2 * err;
            if(e2 >= dy){ err += dy; x += sx; }
            if(e2 <= dx){ err += dx; y += sy; }
        }

        Block draw = activeDrawBlock();
        if(draw != null && draw.isMultiblock()){
            int step = Math.max(1, draw.size);
            int acc = step;
            Point2 lastPlaced = null;
            for(Point2 p : points){
                acc++;
                if(lastPlaced == null || acc >= step){
                    applyBrushDrawAt(p.x, p.y);
                    lastPlaced = p;
                    acc = 0;
                }
            }
            Point2 end = points.peek();
            if(lastPlaced == null || lastPlaced.x != end.x || lastPlaced.y != end.y){
                applyBrushDrawAt(end.x, end.y);
            }
        }else{
            for(Point2 p : points){
                applyBrushDrawAt(p.x, p.y);
            }
        }
    }

    private void applyBrushCells(int cx, int cy, int s, arc.func.Cons<Tile> op){
        s = Math.max(1, s);
        int minX = -s / 2;
        int maxX = minX + s - 1;
        int minY = -s / 2;
        int maxY = minY + s - 1;
        for(int dx = minX; dx <= maxX; dx++){
            for(int dy = minY; dy <= maxY; dy++){
                if(!insideBrush(dx, dy, s, minX, maxX, minY, maxY)) continue;
                int x = cx + dx, y = cy + dy;
                if(!inBounds(x, y)) continue;
                op.get(editor.tile(x, y));
            }
        }
    }

    private boolean insideBrush(int dx, int dy, int s, int minX, int maxX, int minY, int maxY){
        if(s <= 1) return dx == 0 && dy == 0;
        switch(brushShape){
            case square: return true;
            case diamond: return Math.abs(dx - (minX + maxX) / 2f) + Math.abs(dy - (minY + maxY) / 2f) <= (s - 1) / 2f;
            case cross: return dx == 0 || dy == 0;
            default: return Mathf.dst(dx + 0.5f, dy + 0.5f, 0f, 0f) <= s / 2f;
        }
    }

    private void setGridAreaFromSelection(){
        int minX = Math.min(gridStartX, gridEndX);
        int minY = Math.min(gridStartY, gridEndY);
        int maxX = Math.max(gridStartX, gridEndX);
        int maxY = Math.max(gridStartY, gridEndY);
        gridArea = new Recti(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private boolean inBounds(int x, int y){
        return x >= 0 && y >= 0 && x < editor.width() && y < editor.height();
    }

    private boolean isDescendant(Element child, Element ancestor){
        Element cur = child;
        while(cur != null){
            if(cur == ancestor) return true;
            cur = cur.parent;
        }
        return false;
    }

    private Point2 projectRaw(MapView view, float x, float y){
        float offsetx = Reflect.get(MapView.class, view, "offsetx");
        float offsety = Reflect.get(MapView.class, view, "offsety");
        float zoom = Reflect.get(MapView.class, view, "zoom");
        float ratio = 1f / ((float)editor.width() / editor.height());
        float size = Math.min(view.getWidth(), view.getHeight());
        float sclwidth = size * zoom;
        float sclheight = size * zoom * ratio;
        float wx = (x - view.getWidth() / 2f + sclwidth / 2f - offsetx * zoom) / sclwidth * editor.width();
        float wy = (y - view.getHeight() / 2f + sclheight / 2f - offsety * zoom) / sclheight * editor.height();
        if((mode == Mode.draw || mode == Mode.drawLine) && editor.drawBlock != null && editor.drawBlock.size % 2 == 0){
            return new Point2(Mathf.floor(wx - 0.5f), Mathf.floor(wy - 0.5f));
        }
        return new Point2(Mathf.floor(wx), Mathf.floor(wy));
    }

    private Point2 currentMouseTile(){
        Vec2 local = attachedView.screenToLocalCoordinates(new Vec2(Core.input.mouseX(), Core.input.mouseY()));
        return projectRaw(attachedView, local.x, local.y);
    }

    private Point2 pasteOriginForCursor(int cursorX, int cursorY){
        if(clipboard == null || !smartPasting) return new Point2(cursorX, cursorY);
        return new Point2(cursorX - clipboard.anchorX, cursorY - clipboard.anchorY);
    }

    private Vec2 tileToLocal(int x, int y){
        Vec2 viewLocal = Reflect.invoke(MapView.class, attachedView, "unproject", new Object[]{x, y}, int.class, int.class);
        Vec2 stage = attachedView.localToStageCoordinates(new Vec2(viewLocal.x, viewLocal.y));
        return overlay.stageToLocalCoordinates(stage);
    }

    private float cellSize(){
        Vec2 a = tileToLocal(0, 0);
        Vec2 b = tileToLocal(1, 0);
        return Math.max(1f, Math.abs(b.x - a.x));
    }

    private class Overlay extends Element{
        @Override
        public void draw(){
            super.draw();
            if(attachedView == null) return;
            Vec2 s1 = attachedView.localToStageCoordinates(new Vec2(0f, 0f));
            Vec2 s2 = attachedView.localToStageCoordinates(new Vec2(attachedView.getWidth(), attachedView.getHeight()));
            Vec2 l1 = stageToLocalCoordinates(s1);
            Vec2 l2 = stageToLocalCoordinates(s2);
            Rect clip = new Rect(Math.min(l1.x, l2.x), Math.min(l1.y, l2.y), Math.abs(l2.x - l1.x), Math.abs(l2.y - l1.y));
            if(!ScissorStack.push(clip)) return;

            if(advancedGrid) drawAdvancedGrid();
            if(selecting) drawSelection(selectStartX, selectStartY, selectEndX, selectEndY, Pal.accent, 0.18f);
            if(selectingGridArea) drawSelection(gridStartX, gridStartY, gridEndX, gridEndY, Pal.heal, 0.15f);

            if(mode == Mode.paste && clipboard != null){
                if(mobile){
                    ensureMobilePasteOrigin();
                    if(mobilePasteOriginSet){
                        drawClipboardGhost(mobilePasteOriginX, mobilePasteOriginY);
                    }
                }else{
                    Point2 p = currentMouseTile();
                    Point2 o = pasteOriginForCursor(p.x, p.y);
                    drawClipboardGhost(o.x, o.y);
                }
            }

            if(mode == Mode.eraseOre || mode == Mode.draw){
                Point2 p = currentMouseTile();
                int s = mode == Mode.eraseOre ? brushRadius : (activeDrawBlock() != null && activeDrawBlock().isMultiblock() ? activeDrawBlock().size : brushRadius);
                drawBrushOutline(p.x, p.y, s, mode == Mode.eraseOre ? Pal.remove : Pal.accent);
            }

            if(mode == Mode.drawLine){
                Point2 p = currentMouseTile();
                Block draw = activeDrawBlock();
                int span = draw != null && draw.isMultiblock() ? draw.size : brushRadius;
                drawBrushOutline(p.x, p.y, span, Pal.accent);
                if(drawingLine){
                    drawBrushOutline(lineStartX, lineStartY, span, Pal.accent);
                    drawLinePreviewArea();
                    drawBrushOutline(lineEndX, lineEndY, span, Pal.accent);
                }
            }

            if(pastingInProgress){
                Vec2 stage = new Vec2(Core.input.mouseX(), Core.input.mouseY());
                Vec2 local = stageToLocalCoordinates(stage);
                float bw = 80f, bh = 8f;
                float bx = local.x - bw/2f;
                float by = local.y - 24f;
                Draw.z(Layer.overlayUI + 1f);
                Draw.color(Color.black, 0.6f);
                Fill.crect(bx, by, bw, bh);
                Draw.color(Pal.accent);
                Fill.crect(bx, by, bw * pasteProgress, bh);
                Draw.reset();
            }
            ScissorStack.pop();
        }

        private void drawSelection(int sx, int sy, int ex, int ey, Color color, float fillAlpha){
            int minX = Math.min(sx, ex);
            int minY = Math.min(sy, ey);
            int maxX = Math.max(sx, ex) + 1;
            int maxY = Math.max(sy, ey) + 1;
            Vec2 a = tileToLocal(minX, minY);
            Vec2 b = tileToLocal(maxX, maxY);
            float x = Math.min(a.x, b.x), y = Math.min(a.y, b.y);
            float w = Math.abs(b.x - a.x), h = Math.abs(b.y - a.y);
            Draw.z(Layer.end);
            Draw.color(color, fillAlpha);
            Fill.crect(x, y, w, h);
            Draw.color(color);
            Lines.stroke(2f);
            Lines.rect(x, y, w, h);
            Draw.reset();
        }

        private void drawClipboardGhost(int originX, int originY){
            if(clipboard == null) return;
            float size = cellSize();
            Draw.z(Layer.end);

            // micro-optimizations: avoid per-cell allocation and repeated field lookups
            final int W = clipboard.width, H = clipboard.height;
            Vec2 baseV = tileToLocal(originX, originY);
            Vec2 xOffV = tileToLocal(originX + 1, originY);
            Vec2 yOffV = tileToLocal(originX, originY + 1);
            xOffV.sub(baseV);
            yOffV.sub(baseV);
            final float baseX = baseV.x, baseY = baseV.y;
            final float xOffX = xOffV.x, xOffY = xOffV.y, yOffX = yOffV.x, yOffY = yOffV.y;
            final float half = size / 2f;

            for(int cx = 0; cx < W; cx++){
                for(int cy = 0; cy < H; cy++){
                    TileData data = clipboard.get(cx, cy);
                    if(data == null) continue;
                    int tx = originX + cx, ty = originY + cy;
                    boolean fitsCell = inBounds(tx, ty);
                    float px = baseX + xOffX * cx + yOffX * cy;
                    float py = baseY + xOffY * cx + yOffY * cy;
                    float drawX = px + half, drawY = py + half;

                    if(fitsCell){
                        if(data.floor != null) drawRegion(data.floor.uiIcon, drawX, drawY, size, ghostOpacity);
                        if(data.overlay != Blocks.air) drawRegion(data.overlay.uiIcon, drawX, drawY, size, ghostOpacity);
                        if(data.center && data.block != Blocks.air){
                            TextureRegion icon = data.block.fullIcon != null ? data.block.fullIcon : data.block.uiIcon;
                            drawRegion(icon, drawX, drawY, size * data.block.size, ghostOpacity);
                        }
                    }else{
                        // cheap red overlay for out-of-bounds cells; avoid drawing heavy sprites
                        Draw.color(Pal.remove, ghostOpacity);
                        Fill.crect(drawX, drawY, size, size);
                    }
                }
            }

            // outline whole selection in normal accent color; only out-of-bounds cells drawn red above
            Vec2 b = tileToLocal(originX + W, originY + H);
            float x = Math.min(baseX, b.x), y = Math.min(baseY, b.y);
            float w = Math.abs(b.x - baseX), h = Math.abs(b.y - baseY);
            Draw.color(Pal.accent);
            Lines.stroke(2f);
            Lines.rect(x, y, w, h);
            Draw.reset();
        }

        private void drawRegion(TextureRegion region, float x, float y, float size, float alpha){
            if(region == null || region.texture == null) return;
            Draw.color(Color.white, alpha);
            Draw.rect(region, x, y, size, size);
        }

        private void drawBrushOutline(int cx, int cy, int s, Color color){
            IntSet mask = buildBrushMask(cx, cy, s);
            drawMaskOutline(mask, color);
        }

        private void drawLinePreviewArea(){
            Block draw = activeDrawBlock();
            int span = draw != null && draw.isMultiblock() ? draw.size : brushRadius;
            IntSet mask = buildLineBrushMask(lineStartX, lineStartY, lineEndX, lineEndY, span);
            drawMaskOutline(mask, Pal.accent);
        }

            private IntSet buildBrushMask(int cx, int cy, int s){
                IntSet mask = new IntSet();
                s = Math.max(1, s);
                int minX = -s / 2;
                int maxX = minX + s - 1;
                int minY = -s / 2;
                int maxY = minY + s - 1;
                for(int dx = minX; dx <= maxX; dx++){
                    for(int dy = minY; dy <= maxY; dy++){
                        if(!insideBrush(dx, dy, s, minX, maxX, minY, maxY)) continue;
                        int x = cx + dx, y = cy + dy;
                        if(!inBounds(x, y)) continue;
                        mask.add(Point2.pack(x, y));
                    }
                }
                return mask;
            }

        private IntSet buildLineBrushMask(int x1, int y1, int x2, int y2, int s){
            IntSet mask = new IntSet();
            int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
            int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
            int err = dx + dy, x = x1, y = y1;
            while(true){
                IntSet stamp = buildBrushMask(x, y, s);
                stamp.each(mask::add);
                if(x == x2 && y == y2) break;
                int e2 = 2 * err;
                if(e2 >= dy){ err += dy; x += sx; }
                if(e2 <= dx){ err += dx; y += sy; }
            }
            return mask;
        }

        private void drawMaskOutline(IntSet mask, Color color){
            float sz = cellSize();
            Draw.z(Layer.end);
            Draw.color(color);
            Lines.stroke(1.8f);
            mask.each(packed -> {
                int x = Point2.x(packed), y = Point2.y(packed);
                Vec2 p = tileToLocal(x, y);
                if(!mask.contains(Point2.pack(x - 1, y))) Lines.line(p.x, p.y, p.x, p.y + sz);
                if(!mask.contains(Point2.pack(x + 1, y))) Lines.line(p.x + sz, p.y, p.x + sz, p.y + sz);
                if(!mask.contains(Point2.pack(x, y - 1))) Lines.line(p.x, p.y, p.x + sz, p.y);
                if(!mask.contains(Point2.pack(x, y + 1))) Lines.line(p.x, p.y + sz, p.x + sz, p.y + sz);
            });
            Draw.reset();
        }

        private void drawAdvancedGrid(){
            int x = 0, y = 0, w = editor.width(), h = editor.height();
            if(gridArea != null){
                x = gridArea.x;
                y = gridArea.y;
                w = gridArea.width;
                h = gridArea.height;
            }
            if(w <= 0 || h <= 0) return;

            Vec2 min = tileToLocal(x, y);
            Vec2 max = tileToLocal(x + w, y + h);
            float left = Math.min(min.x, max.x), right = Math.max(min.x, max.x);
            float bot = Math.min(min.y, max.y), top = Math.max(min.y, max.y);

            Draw.z(Layer.end);
            Draw.color(Pal.place, 0.65f);
            Lines.stroke(1f);
            int maxDetail = maxGridDetail();
            int steps = 1 << Mathf.clamp(gridDetail, 0, maxDetail);
            for(int i = 1; i < steps; i++){
                int ix = x + (w * i) / steps;
                int iy = y + (h * i) / steps;
                float tx = tileToLocal(ix, y).x;
                float ty = tileToLocal(x, iy).y;
                Lines.line(tx, bot, tx, top);
                Lines.line(left, ty, right, ty);
            }
            Lines.stroke(2f);
            Lines.rect(left, bot, right - left, top - bot);
            Draw.reset();
        }
    }

    private void ensureMobilePasteOrigin(){
        if(!mobile || mode != Mode.paste || clipboard == null) return;
        if(mobilePasteOriginSet) return;
        Point2 center = projectRaw(attachedView, attachedView.getWidth() * 0.5f, attachedView.getHeight() * 0.5f);
        Point2 o = pasteOriginForCursor(center.x, center.y);
        mobilePasteOriginX = o.x;
        mobilePasteOriginY = o.y;
        mobilePasteOriginSet = true;
    }

    private boolean insidePasteGhost(int tileX, int tileY){
        if(clipboard == null || !mobilePasteOriginSet) return false;
        return tileX >= mobilePasteOriginX && tileY >= mobilePasteOriginY
            && tileX < mobilePasteOriginX + clipboard.width
            && tileY < mobilePasteOriginY + clipboard.height;
    }

    private void showMobilePasteBar(){
        if(!mobile || ui == null || ui.editor == null) return;
        if(mobilePasteBar != null) mobilePasteBar.remove();
        mobilePasteBar = new Table(Tex.button);
        mobilePasteBar.defaults().size(72f, 42f).pad(4f);
        mobilePasteBar.button(Icon.ok, Styles.flati, () -> {
            if(mode == Mode.paste && clipboard != null && mobilePasteOriginSet){
                pasteAt(mobilePasteOriginX, mobilePasteOriginY);
            }
        }).tooltip("Confirm paste");
        mobilePasteBar.button(Icon.cancel, Styles.flati, () -> setMode(Mode.none)).tooltip("Cancel");
        ui.editor.addChild(mobilePasteBar);
        mobilePasteBar.pack();
        mobilePasteBar.setPosition((ui.editor.getWidth() - mobilePasteBar.getPrefWidth()) / 2f, 10f);
        mobilePasteBar.toFront();
    }

    private void hideMobilePasteBar(){
        if(mobilePasteBar != null){
            mobilePasteBar.remove();
            mobilePasteBar = null;
        }
    }

    private void handleMobilePinchZoom(){
        if(!mobile || attachedView == null || Core.scene == null) return;
        if(Core.input.isTouched(0) && Core.input.isTouched(1)){
            float x0 = Core.input.mouseX(0), y0 = Core.input.mouseY(0);
            float x1 = Core.input.mouseX(1), y1 = Core.input.mouseY(1);
            float dist = Mathf.dst(x0, y0, x1, y1);
            if(pinchActive){
                float delta = dist - pinchLastDist;
                float zoom = Reflect.get(MapView.class, attachedView, "zoom");
                zoom = Mathf.clamp(zoom - delta * 0.0025f, 0.15f, 20f);
                Reflect.set(MapView.class, attachedView, "zoom", zoom);
            }
            pinchLastDist = dist;
            pinchActive = true;
        }else{
            pinchActive = false;
        }
    }

    private static class Recti{
        final int x, y, width, height;
        Recti(int x, int y, int width, int height){
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static class Clip{
        final int width, height;
        final TileData[] data;
        int anchorX, anchorY;
        Clip(int width, int height){
            this.width = width;
            this.height = height;
            this.data = new TileData[width * height];
            this.anchorX = 0;
            this.anchorY = 0;
        }
        TileData get(int x, int y){ return data[x + y * width]; }
        void set(int x, int y, TileData value){ data[x + y * width] = value; }
    }

    private static class TileData{
        Floor floor;
        Block overlay;
        Block block;
        Team team;
        int rotation;
        byte data, floorData, overlayData;
        int extraData;
        boolean center;

        static TileData get(Tile tile, boolean useFloor, boolean useOre, boolean useBlock){
            TileData out = new TileData();
            out.floor = useFloor ? tile.floor() : null;
            Block overlay = useOre ? tile.overlay() : null;
            out.overlay = overlay != null ? overlay : Blocks.air;
            out.center = tile.isCenter() && useBlock;
            out.block = out.center ? tile.block() : Blocks.air;
            out.team = tile.team();
            out.rotation = tile.build == null ? 0 : tile.build.rotation;
            out.data = tile.data;
            out.floorData = tile.floorData;
            out.overlayData = tile.overlayData;
            out.extraData = tile.extraData;
            return out;
        }

        static TileData empty(){
            TileData out = new TileData();
            out.floor = null;
            out.overlay = Blocks.air;
            out.block = Blocks.air;
            out.team = Team.sharded;
            out.rotation = 0;
            out.center = false;
            return out;
        }

        TileData copy(){
            TileData out = new TileData();
            out.floor = floor;
            out.overlay = overlay;
            out.block = block;
            out.team = team;
            out.rotation = rotation;
            out.data = data;
            out.floorData = floorData;
            out.overlayData = overlayData;
            out.extraData = extraData;
            out.center = center;
            return out;
        }
    }
}
