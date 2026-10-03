package dev.flags.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The console is a single-page application: its routes exist only in the browser. A
 * reload or a pasted link asks the server for one of them, and the server answers with
 * the page that knows what to do with it.
 */
@Controller
class ConsoleController {

    @GetMapping({"/login", "/signup", "/flags", "/flags/{key}", "/keys", "/audit", "/members"})
    String console() {
        return "forward:/index.html";
    }
}
