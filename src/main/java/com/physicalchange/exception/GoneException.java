package com.physicalchange.exception;

/** Recurso que existió pero ya no se puede usar, como una invitación caducada (410). */
public class GoneException extends RuntimeException { public GoneException(String m) { super(m); } }
