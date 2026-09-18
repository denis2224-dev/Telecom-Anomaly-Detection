package md.utm.telecom.incidents.auth.dto;

public record CsrfResponse(String token, String headerName, String parameterName) {}
