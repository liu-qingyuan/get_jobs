package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * In-process Boss contact-only effect. The workbench owns the task/claim lifecycle;
 * the implementation owns the browser session and the single ChatDelivery stage.
 */
public interface BossSingleJobEffect {
    void run(ObjectNode bundle, Function<ObjectNode, ObjectNode> emit, Path directory, BooleanSupplier stopped) throws Exception;
}
