package com.gluonhq.richtextarea;

import com.gluonhq.richtextarea.model.Paragraph;
import com.gluonhq.richtextarea.model.ParagraphDecoration;
import com.gluonhq.richtextarea.viewmodel.RichTextAreaViewModel;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Paint;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.HitInfo;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gluonhq.richtextarea.model.TableDecoration.TABLE_SEPARATOR;

public class ParagraphTile extends HBox {

    private static final double INDENT_PADDING = 20.0;

    // ParagraphTile is HBox
    // | graphicBox | contentPane |
    // If no table, contentPane has single layer of (background + selection + caret shapes, textFlow)
    // If table, contentPane has gridBox, an HBox that can be aligned per paragraph text alignment,
    //     and has a grid of cxr layers

    private Paragraph paragraph;
    private final HBox graphicBox;
    private final Pane contentPane;

    private final List<Layer> layers;

    private final RichTextArea control;
    private final RichTextAreaSkin richTextAreaSkin;
    private final RichTextAreaViewModel viewModel;
    private final ChangeListener<Number> caretPositionListener = (o, ocp, p) -> updateCaretPosition(p.intValue());
    private final ChangeListener<Selection> selectionListener = (o, os, selection) -> updateSelection(selection);

    public ParagraphTile(RichTextAreaSkin richTextAreaSkin) {
        this.richTextAreaSkin = richTextAreaSkin;
        this.control = richTextAreaSkin.getSkinnable();
        this.viewModel = richTextAreaSkin.getViewModel();
        getStyleClass().setAll("paragraph-tile");

        contentPane = new Pane();
        contentPane.setPadding(new Insets(1));
        contentPane.getStyleClass().setAll("content-area");
        layers = new ArrayList<>();

        graphicBox = new HBox();
        graphicBox.getStyleClass().add("graphic-box");
        graphicBox.setAlignment(Pos.TOP_RIGHT);
        getChildren().addAll(graphicBox, contentPane);
        setSpacing(0);
    }

    void setParagraph(Paragraph paragraph, List<Node> fragments, List<Integer> positions, List<IndexRangeColor> background) {
        layers.forEach(Layer::reset);
        layers.clear();
        graphicBox.getChildren().clear();
        contentPane.getChildren().clear();
        viewModel.caretPositionProperty().removeListener(caretPositionListener);
        viewModel.selectionProperty().removeListener(selectionListener);
        if (paragraph == null) {
            contentPane.setPrefWidth(0);
            return;
        }
        this.paragraph = paragraph;
        ParagraphDecoration decoration = paragraph.getDecoration();
        viewModel.caretPositionProperty().addListener(caretPositionListener);
        viewModel.selectionProperty().addListener(selectionListener);
        if (decoration.hasTableDecoration()) {
            if (!fragments.isEmpty()) {
                HBox gridBox = createGridBox(fragments, positions, background, decoration);
                contentPane.getChildren().add(gridBox);
            }
        } else {
            Layer layer = new Layer(paragraph.getStart(), paragraph.getEnd());
            layer.setContent(fragments, background, decoration);
            layers.add(layer);
            contentPane.getChildren().add(layer);
            updateGraphicBox(layer, control.getParagraphGraphicFactory());
            graphicBox.setPadding(new Insets(decoration.getTopInset(), 2, decoration.getBottomInset(), 0));
        }
    }

    private HBox createGridBox(List<Node> fragments, List<Integer> positions, List<IndexRangeColor> background, ParagraphDecoration decoration) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("table");
        int r = decoration.getTableDecoration().getRows();
        int c = decoration.getTableDecoration().getColumns();
        TextAlignment[][] ta = decoration.getTableDecoration().getCellAlignment();
        for (int j = 0; j < c; j++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / (double) c);
            grid.getColumnConstraints().add(cc);
        }
        int index = 0;
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                if (index + 1 >= positions.size()) {
                    break;
                }
                Layer layer = new Layer(positions.get(index), positions.get(index + 1));
                ParagraphDecoration pd = ParagraphDecoration.builder().fromDecoration(decoration).alignment(ta[i][j]).build();
                int tableIndex = index;
                layer.setContent(fragments.stream()
                        .filter(n -> {
                            int p = (int) n.getProperties().getOrDefault(TABLE_SEPARATOR, -1);
                            return (positions.get(tableIndex) <= p && p < positions.get(tableIndex + 1));
                        })
                        .collect(Collectors.toList()), background, pd);
                layer.updatePrefWidth(100);
                layers.add(layer);
                grid.add(layer, j, i);
                if (j == 0) {
                    RowConstraints rc = new RowConstraints();
                    rc.setMinHeight(layer.prefHeight(100));
                    grid.getRowConstraints().add(rc);
                }
                index++;
            }
        }
        HBox gridBox = new HBox(grid);
        gridBox.setPrefHeight(grid.getPrefHeight() + 1);
        gridBox.setPrefWidth(richTextAreaSkin.textFlowPrefWidthProperty.get());
        gridBox.setAlignment(decoration.getAlignment().equals(TextAlignment.LEFT) ? Pos.TOP_LEFT :
                decoration.getAlignment().equals(TextAlignment.RIGHT) ? Pos.TOP_RIGHT : Pos.TOP_CENTER);
        return gridBox;
    }

    private void updateGraphicBox(Layer layer, BiFunction<Integer, ParagraphDecoration.GraphicType, Node> graphicFactory) {
        ParagraphDecoration decoration = paragraph.getDecoration();
        int indentationLevel = decoration.getIndentationLevel();
        Node graphicNode = null;
        if (graphicFactory != null) {
            graphicNode = graphicFactory.apply(indentationLevel, decoration.getGraphicType());
        }
        double spanPrefWidth = Math.max((indentationLevel - (graphicNode == null ? 0 : 1)) * INDENT_PADDING, 0d);

        if (graphicNode == null) {
            graphicBox.setMinWidth(spanPrefWidth);
            graphicBox.setMaxWidth(spanPrefWidth);
            layer.updatePrefWidth(richTextAreaSkin.textFlowPrefWidthProperty.get() - spanPrefWidth);
            return;
        }
        graphicBox.getChildren().add(graphicNode);

        double nodePrefWidth = 0d, nodePrefHeight = 0d;
         if (graphicNode instanceof Label) {
            Label numberedListLabel = (Label) graphicNode;
            String text = numberedListLabel.getText();
            if (text != null) {
                if (text.contains("#")) {
                    AtomicInteger ordinal = new AtomicInteger();
                    viewModel.getParagraphList().stream()
                            .filter(p -> p.getDecoration().getIndentationLevel() == indentationLevel)
                            .peek(p -> {
                                if (p.getDecoration().getGraphicType() == ParagraphDecoration.GraphicType.BULLETED_LIST) {
                                    // restart ordinal if previous paragraph with same indentation is a bulleted list
                                    ordinal.set(0);
                                } else {
                                    ordinal.incrementAndGet();
                                }
                            })
                            .filter(p -> paragraph.equals(p))
                            .findFirst()
                            .ifPresent(p ->
                                    numberedListLabel.setText(text.replace("#", "" + ordinal.get())));
                }

                Font font = layer.getFont();
                numberedListLabel.setFont(font);
                double w = Tools.computeStringWidth(font, numberedListLabel.getText());
                nodePrefWidth = Math.max(w + 1, INDENT_PADDING);
                nodePrefHeight = Tools.computeStringHeight(font, numberedListLabel.getText());
            }
        } else {
            nodePrefWidth = Math.max(graphicNode.prefWidth(-1), INDENT_PADDING);
            nodePrefHeight = graphicNode.prefHeight(nodePrefWidth);
        }

        graphicNode.setTranslateY((layer.getCaretY() - nodePrefHeight)/ 2d);
        double boxPrefWidth = spanPrefWidth + nodePrefWidth;
        graphicBox.setMinWidth(boxPrefWidth);
        graphicBox.setMaxWidth(boxPrefWidth);
        layer.updatePrefWidth(richTextAreaSkin.textFlowPrefWidthProperty.get() - boxPrefWidth);
    }

    void mousePressedListener(MouseEvent e) {
        if (control.isDisabled()) {
            return;
        }
        layers.forEach(l -> {
            if (l.getLayoutBounds().contains(e.getX(), e.getY())) {
                l.mousePressedListener(e);
            }
        });
    }

    void mouseDraggedListener(MouseEvent e) {
        layers.forEach(l -> {
            if (l.getLayoutBounds().contains(e.getX(), e.getY())) {
                l.mouseDraggedListener(e);
            }
        });
    }

    void evictUnusedObjects() {
        layers.forEach(Layer::evictUnusedObjects);
    }

    void updateLayout() {
        if (control == null || viewModel == null) {
            return;
        }
        layers.forEach(l -> {
            l.updateSelection(viewModel.getSelection());
            l.updateCaretPosition(viewModel.getCaretPosition());
        });
    }

    boolean hasCaret() {
        return layers.stream().anyMatch(Layer::hasCaret);
    }

    int getNextRowPosition(double x, boolean down) {
        return layers.stream()
                .findFirst()
                .map(l -> l.getNextRowPosition(x, down))
                .orElse(0);
    }

    private void updateCaretPosition(int caretPosition) {
        layers.forEach(l -> l.updateCaretPosition(caretPosition));
    }

    private void updateSelection(Selection selection) {
        layers.forEach(l -> l.updateSelection(selection));
    }

    private class Layer extends Pane {

        private final Timeline caretTimeline = new Timeline(
                new KeyFrame(Duration.ZERO        , e -> setCaretVisibility(false)),
                new KeyFrame(Duration.seconds(0.5), e -> setCaretVisibility(true)),
                new KeyFrame(Duration.seconds(1.0))
        );

        private final ObservableSet<Path> textBackgroundColorPaths = FXCollections.observableSet();
        private final Path caretShape = new Path();
        private final Path selectionShape = new Path();
        private final TextFlow textFlow = new TextFlow();
        private double textFlowLayoutX, textFlowLayoutY;

        private final int start, end;

        public Layer(int start, int end) {
            this.start = start;
            this.end = end;
            caretTimeline.setCycleCount(Timeline.INDEFINITE);
            textFlow.setFocusTraversable(false);
            textFlow.getStyleClass().setAll("text-flow");
            textFlow.setOnMousePressed(this::mousePressedListener);

            caretShape.setFocusTraversable(false);
            caretShape.getStyleClass().add("caret");
            selectionShape.getStyleClass().setAll("selection");
            textBackgroundColorPaths.addListener(this::updateLayer);

            getChildren().addAll(textBackgroundColorPaths);
            getChildren().addAll(selectionShape, caretShape, textFlow);
            getStyleClass().add("layer");
        }

        @Override
        protected double computePrefHeight(double width) {
            return textFlow.prefHeight(textFlow.getPrefWidth()) + 1;
        }

        void setContent(List<Node> fragments, List<IndexRangeColor> background, ParagraphDecoration decoration) {
            textFlow.getChildren().setAll(fragments);
            addBackgroundPathsToLayers(background);
            textFlow.setTextAlignment(decoration.getAlignment());
            textFlow.setLineSpacing(decoration.getSpacing());
            textFlow.setPadding(new Insets(decoration.getTopInset(), decoration.getRightInset(), decoration.getBottomInset(), decoration.getLeftInset()));

            textFlowLayoutX = 1d + decoration.getLeftInset();
            textFlowLayoutY = 1d + decoration.getTopInset();
        }

        void reset() {
            caretTimeline.stop();
        }

        private void addBackgroundPathsToLayers(List<IndexRangeColor> backgroundIndexRanges) {
            Map<Paint, Path> fillPathMap = backgroundIndexRanges.stream()
                    .map(indexRangeBackground -> {
                        final Path path = new BackgroundColorPath(textFlow.rangeShape(indexRangeBackground.getStart(), indexRangeBackground.getEnd()));
                        path.setStrokeWidth(0);
                        path.setFill(indexRangeBackground.getColor());
                        path.setLayoutX(textFlowLayoutX);
                        path.setLayoutY(textFlowLayoutY);
                        return path;
                    })
                    .collect(Collectors.toMap(Path::getFill, Function.identity(), (p1, p2) -> {
                        Path union = (Path) Shape.union(p1, p2);
                        union.setFill(p1.getFill());
                        return union;
                    }));
            textBackgroundColorPaths.removeIf(path -> !fillPathMap.containsValue(path));
            textBackgroundColorPaths.addAll(fillPathMap.values());
        }

        void mousePressedListener(MouseEvent e) {
            if (e.getButton() == MouseButton.PRIMARY && !(e.isMiddleButtonDown() || e.isSecondaryButtonDown())) {
                HitInfo hitInfo = textFlow.hitTest(new Point2D(e.getX() - textFlowLayoutX, e.getY() - textFlowLayoutY));
                Selection prevSelection = viewModel.getSelection();
                int prevCaretPosition = viewModel.getCaretPosition();
                int insertionIndex = hitInfo.getInsertionIndex();
                if (insertionIndex >= 0) {
                    // get global insertion point, preventing insertionIndex after linefeed
                    int globalInsertionIndex = Math.min(start + insertionIndex, getParagraphLimit() - 1);
                    if (!(e.isControlDown() || e.isAltDown() || e.isShiftDown() || e.isMetaDown() || e.isShortcutDown())) {
                        viewModel.setCaretPosition(globalInsertionIndex);
                        if (e.getClickCount() == 2) {
                            viewModel.selectCurrentWord();
                        } else if (e.getClickCount() == 3) {
                            viewModel.selectCurrentParagraph();
                        } else {
                            richTextAreaSkin.mouseDragStart = globalInsertionIndex;
                            viewModel.clearSelection();
                        }
                    } else if (e.isShiftDown() && e.getClickCount() == 1 && !(e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown())) {
                        int pos = prevSelection.isDefined() ?
                                globalInsertionIndex < prevSelection.getStart() ? prevSelection.getEnd() : prevSelection.getStart() :
                                prevCaretPosition;
                        viewModel.setSelection(new Selection(pos, globalInsertionIndex));
                        viewModel.setCaretPosition(globalInsertionIndex);
                    }
                }
                control.requestFocus();
                e.consume();
            }
            if (richTextAreaSkin.contextMenu.isShowing()) {
                richTextAreaSkin.contextMenu.hide();
            }
        }

        void mouseDraggedListener(MouseEvent e) {
            HitInfo hitInfo = textFlow.hitTest(new Point2D(e.getX() - textFlowLayoutX, e.getY() - textFlowLayoutY));
            if (hitInfo.getInsertionIndex() >= 0) {
                int dragEnd = start + hitInfo.getInsertionIndex();
                viewModel.setSelection(new Selection(richTextAreaSkin.mouseDragStart, dragEnd));
                viewModel.setCaretPosition(dragEnd);
            }
            e.consume();
        }

        void evictUnusedObjects() {
            Set<Font> usedFonts = textFlow.getChildren()
                    .stream()
                    .filter(Text.class::isInstance)
                    .map(t -> ((Text) t).getFont())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<Font> cachedFonts = new ArrayList<>(richTextAreaSkin.getFontCache().values());
            cachedFonts.removeAll(usedFonts);
            richTextAreaSkin.getFontCache().values().removeAll(cachedFonts);

            Set<Image> usedImages = textFlow.getChildren()
                    .stream()
                    .filter(ImageView.class::isInstance)
                    .map(t -> ((ImageView) t).getImage())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<Image> cachedImages = new ArrayList<>(richTextAreaSkin.getImageCache().values());
            cachedImages.removeAll(usedImages);
            richTextAreaSkin.getImageCache().values().removeAll(cachedImages);
        }

        double getCaretY() {
            var pathElements = textFlow.caretShape(0, false);
            return Stream.of(pathElements)
                    .filter(LineTo.class::isInstance)
                    .map(LineTo.class::cast)
                    .findFirst().map(LineTo::getY)
                    .orElse(0d);
        }

        Font getFont() {
            Text textNode = textFlow.getChildren().stream()
                    .filter(Text.class::isInstance)
                    .map(Text.class::cast)
                    .findFirst()
                    .orElse(null);
            return Font.font(textNode != null ? textNode.getFont().getSize() : 12d);
        }

        int getNextRowPosition(double x, boolean down) {
            Bounds caretBounds = caretShape.getLayoutBounds();
            double nextRowPos = x < 0d ?
                    down ?
                            caretBounds.getMaxY() + textFlow.getLineSpacing() :
                            caretBounds.getMinY() - textFlow.getLineSpacing() :
                    caretBounds.getCenterY();
            double xPos = x < 0d ? caretBounds.getMaxX() : x;
            HitInfo hitInfo = textFlow.hitTest(new Point2D(xPos, nextRowPos));
            return start + hitInfo.getInsertionIndex();
        }

        void updatePrefWidth(double prefWidth) {
            textFlow.setPrefWidth(prefWidth);
        }

        boolean hasCaret() {
            return !caretShape.getElements().isEmpty();
        }

        private void updateCaretPosition(int caretPosition) {
            caretShape.getElements().clear();
            if (!control.isFocused() || paragraph == null || caretPosition < start || getParagraphLimit() <= caretPosition) {
                caretTimeline.stop();
                return;
            }
            if (caretPosition < 0 || !control.isEditable()) {
                caretTimeline.stop();
            } else {
                var pathElements = textFlow.caretShape(caretPosition - start, true);
                if (pathElements.length > 0) {
                    caretShape.getElements().addAll(pathElements);
                    // prevent tiny caret
                    if (caretShape.getLayoutBounds().getHeight() < 5) {
                        double originX = caretShape.getElements().stream()
                                .filter(MoveTo.class::isInstance)
                                .map(MoveTo.class::cast)
                                .findFirst()
                                .map(MoveTo::getX)
                                .orElse(0d);
                        caretShape.getElements().add(new LineTo(originX, 16));
                    }
                    richTextAreaSkin.lastValidCaretPosition = caretPosition;
                    caretTimeline.play();
                }
            }
            caretShape.setLayoutX(textFlowLayoutX);
            caretShape.setLayoutY(textFlowLayoutY);
        }

        private int getParagraphLimit() {
            int limit = end;
            if (paragraph.equals(richTextAreaSkin.lastParagraph)) {
                // at the end of the last paragraph there is no linefeed, so we need
                // an extra position for the caret
                limit += 1;
            }
            return limit;
        }

        private void setCaretVisibility(boolean on) {
            if (caretShape.getElements().size() > 0) {
                // Opacity is used since we don't want the changing caret bounds to affect the layout
                // Otherwise text appears to be jumping
                caretShape.setOpacity(on ? 1 : 0);
            }
        }

        private void updateSelection(Selection selection) {
            selectionShape.getElements().clear();
            if (selection != null && selection.isDefined() && !(start > selection.getEnd() || end <= selection.getStart())) {
                PathElement[] pathElements = textFlow.rangeShape(
                        Math.max(start, selection.getStart()) - start,
                        Math.min(end, selection.getEnd()) - start);
                if (pathElements.length > 0) {
                    selectionShape.getElements().setAll(pathElements);
                }
            }
            selectionShape.setLayoutX(textFlowLayoutX);
            selectionShape.setLayoutY(textFlowLayoutY);
        }

        private void updateLayer(SetChangeListener.Change<? extends Path> change) {
            if (change.wasAdded()) {
                getChildren().add(0, change.getElementAdded());
            } else if (change.wasRemoved()) {
                getChildren().remove(change.getElementRemoved());
            }
        }
    }
}
