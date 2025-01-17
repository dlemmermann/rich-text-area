package com.gluonhq.richtext.viewmodel;

import com.gluonhq.richtext.Selection;
import javafx.beans.binding.BooleanBinding;

class ActionCmdCut implements ActionCmd {
    @Override
    public void apply(RichTextAreaViewModel viewModel) {
        if (viewModel.isEditable()) {
            viewModel.clipboardCopy(true);
        }
    }

    @Override
    public BooleanBinding getDisabledBinding(RichTextAreaViewModel viewModel) {
        return viewModel.selectionProperty().isEqualTo(Selection.UNDEFINED).or(viewModel.editableProperty().not());
    }
}
