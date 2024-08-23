package org.fitznet.fitznetapi.controller;

import java.util.List;

import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UserController {

    @Autowired
    UserService userService;

    static final Logger log = LoggerFactory.getLogger(UserController.class);
    @Autowired
    private UserRepository userRepository;

    @PostMapping("/user/create")
    public User createUser(@RequestBody UserDTO user) {
        log.info("Request at /user - {}", user.toString());
        user.sanitizeUsernameAndEmail();
        performRequestValidations(user);
        return userService.saveUser(
                User.builder()
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .password(user.getPassword())
                        .build());
    }

    @PostMapping("/user/read")
    public User readUser(@RequestBody String username) {
        log.info("Request for /user/read - {}", username);
        return userService.readByUsername(username);
    }

    @GetMapping("/user/readAll")
    public List<User> readAllUsers() {
        log.info("Request for /user/readAll");
        return userService.findAll();
    }

    @DeleteMapping("/user/delete")
    public void deleteUser(@RequestBody DeleteUserRequestDto user) {
        log.info("Request for /delete");
        if (!doesUserAlreadyExist(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found in db");
        }
        userService.deleteUser(user.getUsername());
    }

    @PatchMapping("/user/update")
    public void updateUser(@RequestBody UpdateUserRequestDto updateUserDto) {
        log.info("Request for /update");
        userService.updateUser(updateUserDto);
    }

    private void performRequestValidations(UserDTO user) {
        if (doesUserAlreadyExist(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }

        if (isEmailAlreadyInUse(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email in use");
        }
    }

    private boolean isEmailAlreadyInUse(UserDTO user) {
        var possibleUser = userRepository.findByEmail(user.getEmail());
        log.info("Checking to see if email {} exists in db", user.getEmail());
        return null != possibleUser;
    }

    private boolean doesUserAlreadyExist(UserDTO user) {
        var possibleUser = userRepository.findByUsername(user.getUsername());
        log.info("Checking to see if user {} exists in db", user.getUsername());
        return null != possibleUser;
    }

    private boolean doesUserAlreadyExist(String username) {
        var possibleUser = userRepository.findByUsername(username);
        log.info("Checking to see if user {} exists in db", username);
        return null != possibleUser;
    }
}
