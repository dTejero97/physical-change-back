package com.physicalchange.exception;

/** Petición válida en formato pero incoherente con los datos (400). */
public class BadRequestException extends RuntimeException { public BadRequestException(String m) { super(m); } }
