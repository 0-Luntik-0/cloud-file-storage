package com.example.project.services.auth;

import com.example.project.exceptions.UserNotValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.List;

@RequiredArgsConstructor
@Service
public class ValidationService {

    public void checkForValidationErrors(BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            StringBuilder errorMsg = new StringBuilder();
            List<FieldError> errors = bindingResult.getFieldErrors();
            for (FieldError error : errors) {
                errorMsg
                        .append(error.getDefaultMessage())
                        .append(";");
            }
            throw new UserNotValidationException(errorMsg.toString());
        }

    }
}
