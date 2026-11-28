package com.getjobs.application.workbench;

final class WorkbenchException extends RuntimeException {
    final int status;
    final com.fasterxml.jackson.databind.node.ObjectNode detail;
    WorkbenchException(int status, String message) { this(status, message, null); }
    WorkbenchException(int status, String message, com.fasterxml.jackson.databind.node.ObjectNode detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }
}
