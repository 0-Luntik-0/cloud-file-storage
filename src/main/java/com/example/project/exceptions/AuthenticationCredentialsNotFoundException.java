package com.example.project.exceptions;

public class AuthenticationCredentialsNotFoundException extends RuntimeException {
    public AuthenticationCredentialsNotFoundException() {
        super("Пользователь не авторизован");
    }
}
