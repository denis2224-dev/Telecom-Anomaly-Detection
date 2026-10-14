package md.utm.telecom.notifications.controller;

public record PushConfigurationResponse(boolean enabled, String vapidPublicKey) {}
