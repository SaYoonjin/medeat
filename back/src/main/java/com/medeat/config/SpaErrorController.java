package com.medeat.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class SpaErrorController implements ErrorController {

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttribute == null
                ? HttpStatus.INTERNAL_SERVER_ERROR.value()
                : Integer.parseInt(statusAttribute.toString());
        String path = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        String accept = request.getHeader("Accept");

        if (status == HttpStatus.NOT_FOUND.value()
                && accept != null
                && accept.contains("text/html")
                && !path.startsWith("/api/")
                && !path.startsWith("/uploads/")
                && !path.startsWith("/ws-chat")) {
            return new ModelAndView("forward:/index.html");
        }

        return ResponseEntity.status(status).body("Request failed with status " + status);
    }
}
