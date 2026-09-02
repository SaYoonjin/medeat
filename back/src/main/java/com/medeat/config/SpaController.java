package com.medeat.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
            "/login",
            "/signup",
            "/ai-chat",
            "/auth/{*path}",
            "/challenge",
            "/challenge/{*path}",
            "/my/{*path}",
            "/community",
            "/community/{*path}",
            "/dashboard",
            "/dashboard-medeat",
            "/diet",
            "/diet/{*path}",
            "/disease",
            "/disease/{*path}",
            "/medication/{*path}",
            "/mypage",
            "/mypage/{*path}",
            "/notifications"
    })
    public String forwardToFrontend() {
        return "forward:/index.html";
    }
}
