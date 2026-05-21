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
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.CodeTextModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JavaFX entry point for the stamp-adoc-explorer.
 *
 * <p>v0 skeleton: loads the default fixture
 * ({@value #DEFAULT_FILE_REL_TO_HOME}) into a {@link CodeTextModel} and
 * displays it in a {@link RichTextArea}. No STAMP styling yet — that
 * arrives once the {@code HistoryWalkStampExtractor} is wired up and we
 * can translate {@link design.knowledge.stamp.adoc.model.StampFile}
 * records into RichTextArea style applications.
 *
 * <p>Default fixture is the ike-docs {@code getting-started.adoc} with its
 * two known commits — a known-good development target.
 */
public final class App extends Application {

    /** Fixture path relative to the user's home directory. */
    static final String DEFAULT_FILE_REL_TO_HOME =
            "ike-dev/ike-docs/src/site/asciidoc/getting-started.adoc";

    private static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), DEFAULT_FILE_REL_TO_HOME);

    /**
     * JavaFX lifecycle entry point.
     *
     * @param stage the primary stage provided by the JavaFX runtime
     */
    @Override
    public void start(Stage stage) {
        CodeTextModel model = new CodeTextModel();
        loadInto(model, DEFAULT_FILE, stage);

        RichTextArea area = new RichTextArea(model);
        BorderPane root = new BorderPane(area);

        Scene scene = new Scene(root, 1100, 750);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Read {@code file} as UTF-8 and insert it at the start of {@code model}.
     * Sets the stage title to reflect the loaded path or load error.
     *
     * @param model destination text model
     * @param file  fixture file to load
     * @param stage stage whose title reflects load status
     */
    private static void loadInto(CodeTextModel model, Path file, Stage stage) {
        try {
            String content = Files.readString(file);
            model.insertText(TextPos.ZERO, content);
            stage.setTitle("stamp-adoc-explorer — " + file);
        } catch (IOException ex) {
            String message = "Failed to read " + file + "\n\n" + ex.getMessage();
            model.insertText(TextPos.ZERO, message);
            stage.setTitle("stamp-adoc-explorer — load error");
        }
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
