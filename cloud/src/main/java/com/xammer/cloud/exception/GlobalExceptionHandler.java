// package com.xammer.cloud.exception;

// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.security.authentication.BadCredentialsException;
// import org.springframework.web.bind.annotation.ControllerAdvice;
// import org.springframework.web.bind.annotation.ExceptionHandler;
// import org.springframework.web.context.request.WebRequest;
// import java.util.Date;
// import java.util.HashMap;
// import java.util.Map;

// @ControllerAdvice
// public class GlobalExceptionHandler {

//     @ExceptionHandler(Exception.class)
//     public ResponseEntity<?> globalExceptionHandler(Exception ex, WebRequest request) {
//         Map<String, Object> body = new HashMap<>();
//         body.put("timestamp", new Date());
//         body.put("message", "An unexpected error occurred: " + ex.getMessage());
//         return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
//     }

//     @ExceptionHandler(BadCredentialsException.class)
//     public ResponseEntity<?> badCredentialsExceptionHandler(BadCredentialsException ex, WebRequest request) {
//         Map<String, Object> body = new HashMap<>();
//         body.put("timestamp", new Date());
//         body.put("message", "Invalid username or password");
//         return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
//     }
// }