/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.clementlevallois.linkprediction.controller;

/**
 *
 * @author LEVALLOIS
 */
/**
 * Additional columns used in data laboratory for link predictions.
 */
public enum LinkPredictionColumn {

    ADDED_IN_RUN("added_in_run"), LAST_VALUE("last_link_prediction_value"), LP_ALGORITHM("link_prediction_algorithm");

    // Column name
    private String name;

    /**
     * Returns name of the column.
     *
     * @return Column name
     */
    public String getName() {
        return name;
    }

    LinkPredictionColumn(String name) {
        this.name = name;
    }
}