package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

import java.util.Collections;
import java.util.List;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UserControllerTest {

  @Mock private UserService userService;

  @Mock private UserRepository userRepository;

  @InjectMocks private UserController userController;

  private AutoCloseable mocks;

  @BeforeEach
  public void setUp() {
    mocks = openMocks(this);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close(); // Properly clean up resources
    }
  }

  @Test
  void createUserShouldReturnCreatedUser() {
    UserDTO userDTO = new UserDTO("mattlol85", "testPassword", "test@example.com");
    User user =
        User.builder()
            .username("mattlol85")
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userService.saveUser(any(User.class))).thenReturn(user);
    when(userRepository.findByUsername(userDTO.getUsername()))
        .thenReturn(null); // Ensuring user doesn't exist

    User createdUser = userController.createUser(userDTO);

    assertNotNull(createdUser);
    assertEquals("mattlol85", createdUser.getUsername());
    verify(userService, times(1)).saveUser(any(User.class));
  }

  @Test
  void readUserShouldReturnUserWhenUserExists() {
    String username = "mattlol85";
    User user =
        User.builder()
            .username(username)
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userService.readByUsername(username)).thenReturn(user);

    User foundUser = userController.readUser(username);

    assertNotNull(foundUser);
    assertEquals(username, foundUser.getUsername());
    verify(userService, times(1)).readByUsername(username);
  }

  @Test
  void readUserShouldReturnNullWhenUserDoesNotExist() {
    String username = "unknownUser";

    when(userService.readByUsername(username)).thenReturn(null);

    User foundUser = userController.readUser(username);

    assertNull(foundUser);
    verify(userService, times(1)).readByUsername(username);
  }

  @Test
  void readAllUsersShouldReturnListOfUsers() {
    User user =
        User.builder()
            .username("mattlol85")
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userService.findAll()).thenReturn(Collections.singletonList(user));

    List<User> users = userController.readAllUsers();

    assertNotNull(users);
    assertEquals(1, users.size());
    assertEquals("mattlol85", users.get(0).getUsername());
    verify(userService, times(1)).findAll();
  }

  @Test
  void deleteUserShouldDeleteUserWhenUserExists() {
    DeleteUserRequestDto deleteUserRequestDto = new DeleteUserRequestDto();
    deleteUserRequestDto.setUsername("mattlol85");
    when(userRepository.findByUsername(deleteUserRequestDto.getUsername())).thenReturn(new User());
    doNothing().when(userService).deleteUser(deleteUserRequestDto.getUsername());

    userController.deleteUser(deleteUserRequestDto);

    verify(userService, times(1)).deleteUser(deleteUserRequestDto.getUsername());
  }

  @Test
  void deleteUserShouldThrowNotFoundExceptionWhenUserDoesNotExist() {
    DeleteUserRequestDto deleteUserRequestDto = new DeleteUserRequestDto();
    deleteUserRequestDto.setUsername("unknownUser");

    when(userRepository.findByUsername(deleteUserRequestDto.getUsername())).thenReturn(null);

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class, () -> userController.deleteUser(deleteUserRequestDto));

    assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    verify(userService, times(0)).deleteUser(deleteUserRequestDto.getUsername());
  }

  @Test
  void updateUserShouldUpdateUserSuccessfully() {
    UpdateUserRequestDto updateUserRequestDto =
        new UpdateUserRequestDto("mattlol85", "newEmail@example.com", "newPassword", "");

    doNothing().when(userService).updateUser(any(UpdateUserRequestDto.class));

    userController.updateUser(updateUserRequestDto);

    verify(userService, times(1)).updateUser(any(UpdateUserRequestDto.class));
  }
}
