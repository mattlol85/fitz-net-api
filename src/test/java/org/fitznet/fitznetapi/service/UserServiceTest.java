package org.fitznet.fitznetapi.service;

import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void saveUser_ShouldSaveAndReturnUser() {
        User user = User.builder().username("mattlol85").email("test@example.com").password("testPassword").build();

        when(userRepository.save(any(User.class))).thenReturn(user);

        User savedUser = userService.saveUser(user);

        assertNotNull(savedUser);
        assertEquals("mattlol85", savedUser.getUsername());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void deleteUser_ShouldDeleteUserByUsername() {
        String username = "mattlol85";

        doNothing().when(userRepository).deleteByUsername(username);

        userService.deleteUser(username);

        verify(userRepository, times(1)).deleteByUsername(username);
    }

    @Test
    void readByUsername_ShouldReturnUser_WhenUserExists() {
        String username = "mattlol85";
        User user = User.builder().username(username).email("test@example.com").password("testPassword").build();

        when(userRepository.findByUsername(username)).thenReturn(user);

        User foundUser = userService.readByUsername(username);

        assertNotNull(foundUser);
        assertEquals(username, foundUser.getUsername());
        verify(userRepository, times(1)).findByUsername(username);
    }

    @Test
    void readByUsername_ShouldReturnNull_WhenUserDoesNotExist() {
        String username = "unknownUser";

        when(userRepository.findByUsername(username)).thenReturn(null);

        User foundUser = userService.readByUsername(username);

        assertNull(foundUser);
        verify(userRepository, times(1)).findByUsername(username);
    }

    @Test
    void updateUser_ShouldUpdateUsername_WhenUserExists() {
        String oldUsername = "mattlol85";
        String newUsername = "mattnew85";
        User user = User.builder().username(oldUsername).email("test@example.com").password("testPassword").build();
        UpdateUserRequestDto updateUserRequestDto =
                new UpdateUserRequestDto(oldUsername, newUsername, null, null);

        when(userRepository.findByUsername(oldUsername)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.updateUser(updateUserRequestDto);

        assertEquals(newUsername, user.getUsername());
        verify(userRepository, times(1)).findByUsername(oldUsername);
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void updateUser_ShouldDoNothing_WhenUserDoesNotExist() {
        String oldUsername = "unknownUser";
        String newUsername = "newUser";
        UpdateUserRequestDto updateUserRequestDto =
                new UpdateUserRequestDto(oldUsername, newUsername, null, null);

        when(userRepository.findByUsername(oldUsername)).thenReturn(null);

        userService.updateUser(updateUserRequestDto);

        verify(userRepository, times(1)).findByUsername(oldUsername);
        verify(userRepository, times(0)).save(any(User.class));
    }

    @Test
    void findAll_ShouldReturnListOfUsers() {
        User user = User.builder()
                .username("mattlol85")
                .email("test@example.com")
                .password("testPassword")
                .build();

        when(userRepository.findAll()).thenReturn(Collections.singletonList(user));

        List<User> users = userService.findAll();

        assertNotNull(users);
        assertEquals(1, users.size());
        assertEquals("mattlol85", users.get(0).getUsername());
        verify(userRepository, times(1)).findAll();
    }
}
