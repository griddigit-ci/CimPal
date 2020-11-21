/*
 * Copyright (c) 2020. All rights reserved.
 */
package gui;

import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;


public class ComboBoxCell extends TableCell<TableColumnsSetup, String> {

    private ComboBox<String> comboBox;
    //private ComboBox comboBox;


    public ComboBoxCell()
    {
        comboBox = new ComboBox();
        //comboBox.setPrefWidth(TableCell.USE_COMPUTED_SIZE);
        //comboBox.setMinWidth(comboBox.getBoundsInParent().getWidth());
        //comboBox.getParent().with
        //comboBox.setMinWidth();
                //ell.setPrefHeight(Control.USE_COMPUTED_SIZE);
        //text.wrappingWidthProperty().bind(valueColumn.widthProperty());
        //text.textProperty().bind(cell.itemProperty());
        //comboBox.set
    }


    @Override
    public void startEdit()
    {
        if ( !isEmpty() )
        {
            super.startEdit();
            comboBox.setItems( getTableView().getItems().get( getIndex() ).getColumn1List() );
            comboBox.getSelectionModel().select( getItem() );
          /*  comboBox.focusedProperty().addListener((observable, oldValue, newValue) -> {
                if ( !newValue )
                {
                    commitEdit( comboBox.getSelectionModel().getSelectedItem() );
                }
            });*/

          comboBox.setOnAction(event -> {
              commitEdit( comboBox.getSelectionModel().getSelectedItem() );
          });
          //what is commented was the original, but the new code above is better it fixes the effect that you need to click on a particular
            //place in order to have to have the value accepted
            /*comboBox.focusedProperty().addListener(new ChangeListener<Boolean>()
            {
                @Override
                public void changed( ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue )
                {
                    if ( !newValue )
                    {
                        commitEdit( comboBox.getSelectionModel().getSelectedItem() );
                    }
                }
            } );*/

            setText( null );
            setGraphic( comboBox );
        }
    }


    @Override
    public void cancelEdit()
    {
        super.cancelEdit();

        setText(getItem());
        setGraphic( null );
    }

    @Override
    public void updateItem( String item, boolean empty )
    {
        super.updateItem( item, empty );

        if ( empty )
        {
            setText( null );
            setGraphic( null );
        }
        else
        {
            if ( isEditing() )
            {
                setText( null );
                setGraphic( comboBox );
            }
            else
            {
                setText( getItem() );
                setGraphic( null );
            }
        }
    }

}
