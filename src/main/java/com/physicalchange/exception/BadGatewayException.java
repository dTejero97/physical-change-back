package com.physicalchange.exception;

/** Un servicio externo (p. ej. una red social al canjear el code de OAuth) no ha aceptado la petición: 502. El detalle va solo al log. */
public class BadGatewayException extends RuntimeException { public BadGatewayException(String m) { super(m); } }
