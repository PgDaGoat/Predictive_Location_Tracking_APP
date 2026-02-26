package com.example.lab3;

public class itemData {
    String itemName;
    String itemValue;

    itemData(String itemName, String itemValue) {
        this.itemName = itemName;
        this.itemValue = itemValue;
    }
    void changeValue(String itemValue) {
        this.itemValue = itemValue;
    }
}
