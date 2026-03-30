package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.base.exception.UseException;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.example.kbsystemproject.entity.UserToken;
import org.example.kbsystemproject.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/user/")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("login")
    public Mono<ResponseVO<UserToken>> login() throws UseException {
        return userService.login("test@gmail.com", "test1234567")
                .map(ResponseBuilder::success);

    }

    @PostMapping("register")
    public Mono<ResponseVO<Boolean>> register() {
        return userService.register("test@gmail.com", "test", "test1234567")
                .map(ResponseBuilder::success);
    }

    @GetMapping("refresh-token")
    @ResponseBody
    public Mono<ResponseVO<String>> refreshToken(
            @RequestParam("email") String useEmail,
            @RequestParam("refresh-token") String refreshToken
    ) {
        return userService.getAccessToken(useEmail, refreshToken)
                .map(ResponseBuilder::success);
    }
}
