package com.agentgate.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the bundled frontend's index.html for its client-side routes, so a reload or a shared link
 * like /executions/{id} opens the app instead of a 404. Only effective in jars built with -PwithFrontend.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/workflows", "/workflows/**", "/executions", "/executions/**", "/approvals"})
    public String index() {
        return "forward:/index.html";
    }
}
