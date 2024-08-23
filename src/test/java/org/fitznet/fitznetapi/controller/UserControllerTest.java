package org.fitznet.fitznetapi.controller;

import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserController userController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void createUser_ShouldReturnCreatedUser() {
        UserDTO userDTO = new UserDTO("mattlol85", "testPassword", "test@example.com");
        User user = User.builder().username("mattlol85").email("test@example.com").password("testPassword").build();

        when(userService.saveUser(any(User.class))).thenReturn(user);
        when(userRepository.findByUsername(userDTO.getUsername())).thenReturn(null); // Ensuring user doesn't exist

        User createdUser = userController.createUser(userDTO);

        assertNotNull(createdUser);
        assertEquals("mattlol85", createdUser.getUsername());
        verify(userService, times(1)).saveUser(any(User.class));
    }

    @Test
    void readUser_ShouldReturnUser_WhenUserExists() {
        String username = "mattlol85";
        User user = User.builder().username(username).email("test@example.com").password("testPassword").build();

        when(userService.readByUsername(username)).thenReturn(user);

        User foundUser = userController.readUser(username);

        assertNotNull(foundUser);
        assertEquals(username, foundUser.getUsername());
        verify(userService, times(1)).readByUsername(username);
    }

    @Test
    void readUser_ShouldReturnNull_WhenUserDoesNotExist() {
        String username = "unknownUser";

        when(userService.readByUsername(username)).thenReturn(null);

        User foundUser = userController.readUser(username);

        assertNull(foundUser);
        verify(userService, times(1)).readByUsername(username);
    }

    @Test
    void readAllUsers_ShouldReturnListOfUsers() {
        User user = User.builder().username("mattlol85").email("test@example.com").password("testPassword").build();

        when(userService.findAll()).thenReturn(Collections.singletonList(user));

        List<User> users = userController.readAllUsers();

        assertNotNull(users);
        assertEquals(1, users.size());
        assertEquals("mattlol85", users.get(0).getUsername());
        verify(userService, times(1)).findAll();
    }

    @Test
    void deleteUser_ShouldDeleteUser_WhenUserExists() {
        DeleteUserRequestDto deleteUserRequestDto = new DeleteUserRequestDto();
        deleteUserRequestDto.setUsername("mattlol85");
        when(userRepository.findByUsername(deleteUserRequestDto.getUsername())).thenReturn(new User());
        doNothing().when(userService).deleteUser(deleteUserRequestDto.getUsername());

        userController.deleteUser(deleteUserRequestDto);

        verify(userService, times(1)).deleteUser(deleteUserRequestDto.getUsername());
    }

    @Test
    void deleteUser_ShouldThrowNotFoundException_WhenUserDoesNotExist() {
        DeleteUserRequestDto deleteUserRequestDto = new DeleteUserRequestDto();
        deleteUserRequestDto.setUsername("unknownUser");

        when(userRepository.findByUsername(deleteUserRequestDto.getUsername())).thenReturn(null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> userController.deleteUser(deleteUserRequestDto));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode()); // or exception.getRawStatusCode()
        verify(userService, times(0)).deleteUser(deleteUserRequestDto.getUsername());
    }

    @Test
    void updateUser_ShouldUpdateUserSuccessfully() {
        UpdateUserRequestDto updateUserRequestDto = new UpdateUserRequestDto("mattlol85", "newEmail@example.com", "newPassword", "");

        doNothing().when(userService).updateUser(any(UpdateUserRequestDto.class));

        userController.updateUser(updateUserRequestDto);

        verify(userService, times(1)).updateUser(any(UpdateUserRequestDto.class));
    }
}
