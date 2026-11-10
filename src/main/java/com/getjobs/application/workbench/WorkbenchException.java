package com.getjobs.application.workbench;

final class WorkbenchException extends RuntimeException {
    final int status;
    WorkbenchException(int status, String message) { super(message); this.status = status; }
}
