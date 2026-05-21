/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;

/**
 * JavaFX entry point for the stamp-adoc-explorer.
 *
 * <p>v0 skeleton: instantiates a {@link RichTextArea} in a borderless window.
 * Subsequent iterations will: open a default file (the
 * {@code getting-started.adoc} fixture in {@code ~/ike-dev/ike-docs}),
 * run Level-2 history-walk extraction, and apply per-STAMP styled spans
 * via {@code RichTextArea}'s incubating style API.
 */
public final class App extends Application {

    /**
     * JavaFX lifecycle entry point.
     *
     * @param stage the primary stage provided by the JavaFX runtime
     */
    @Override
    public void start(Stage stage) {
        RichTextArea area = new RichTextArea();

        BorderPane root = new BorderPane(area);

        Scene scene = new Scene(root, 1100, 750);
        stage.setTitle("stamp-adoc-explorer");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Process entry point. Delegates to {@link Application#launch(String...)}.
     *
     * @param args command-line arguments forwarded to the JavaFX runtime
     */
    public static void main(String[] args) {
        launch(args);
    }
}
