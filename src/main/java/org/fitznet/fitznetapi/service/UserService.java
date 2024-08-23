package org.fitznet.fitznetapi.service;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static java.util.Objects.nonNull;

@Slf4j
@Service
public class UserService {

    @Autowired
    UserRepository userRepository;

    public User saveUser(User user) {
        log.info("Saving user... - {}", user.getUsername());
        return userRepository.save(user);
    }

    public void deleteUser(String username) {
        log.info("Deleting user - {}", username);
        userRepository.deleteByUsername(username);
    }

    public User readByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public void updateUser(UpdateUserRequestDto updateRequest) {
        if(nonNull(updateRequest.getUsername()) && nonNull(updateRequest.getUpdatedUsername())) {
            log.info("Valid username update request received.");
            var user = userRepository.findByUsername(updateRequest.getUsername());
            if (null != user) {
                log.info("User found, updating username in db.");
                user.setUsername(updateRequest.getUpdatedUsername());
                userRepository.save(user);
            }
        }
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }
}
