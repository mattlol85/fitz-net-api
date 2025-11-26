package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

import java.util.Collections;
import java.util.List;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.DeleteUserRequestDto;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.ReadSingleAccountRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
      mocks.close();
    }
  }

  @Test
  void createUserShouldReturnCreatedUser() {
    UserDTO userDTO = new UserDTO("mattlol85", "test@example.com", "testPassword123");
    User user =
        User.builder()
            .id("123")
            .username("mattlol85")
            .email("test@example.com")
            .password("hashedPassword")
            .build();

    when(userService.saveUser(any(User.class))).thenReturn(user);
    when(userRepository.findByUsername(userDTO.getUsername())).thenReturn(null);
    when(userRepository.findByEmail(userDTO.getEmail())).thenReturn(null);

    ResponseEntity<UserResponseDto> response = userController.createUser(userDTO);

    assertNotNull(response);
    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("mattlol85", response.getBody().getUsername());
    assertEquals("test@example.com", response.getBody().getEmail());
    verify(userService, times(1)).saveUser(any(User.class));
  }

  @Test
  void readUserShouldReturnUserWhenUserExists() {
    String username = "mattlol85";
    User user =
        User.builder()
            .id("123")
            .username(username)
            .email("test@example.com")
            .password("hashedPassword")
            .build();

    when(userService.readByUsername(username)).thenReturn(user);

    ResponseEntity<UserResponseDto> response = userController.readUser(new ReadSingleAccountRequestDto(username));

    assertNotNull(response);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(username, response.getBody().getUsername());
    assertEquals("test@example.com", response.getBody().getEmail());
    verify(userService, times(1)).readByUsername(username);
  }

  @Test
  void readUserShouldThrowNotFoundWhenUserDoesNotExist() {
    String username = "unknownUser";

    when(userService.readByUsername(username)).thenReturn(null);

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> userController.readUser(new ReadSingleAccountRequestDto(username)));

    assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    verify(userService, times(1)).readByUsername(username);
  }

  @Test
  void readAllUsersShouldReturnListOfUsers() {
    User user =
        User.builder()
            .id("123")
            .username("mattlol85")
            .email("test@example.com")
            .password("hashedPassword")
            .build();

    when(userService.findAll()).thenReturn(Collections.singletonList(user));

    ResponseEntity<List<UserResponseDto>> response = userController.readAllUsers();

    assertNotNull(response);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().size());
    assertEquals("mattlol85", response.getBody().getFirst().getUsername());
    assertEquals("test@example.com", response.getBody().getFirst().getEmail());
    verify(userService, times(1)).findAll();
  }

  @Test
  void deleteUserShouldDeleteUserWhenUserExists() {
    DeleteUserRequestDto deleteUserRequestDto = new DeleteUserRequestDto();
    deleteUserRequestDto.setUsername("mattlol85");
    when(userRepository.findByUsername(deleteUserRequestDto.getUsername())).thenReturn(new User());
    doNothing().when(userService).deleteUser(deleteUserRequestDto.getUsername());

    ResponseEntity<Void> response = userController.deleteUser(deleteUserRequestDto);

    assertNotNull(response);
    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
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
        new UpdateUserRequestDto("mattlol85", "newUsername", "newEmail@example.com", "newEmail@example.com", "newPassword123");

    User updatedUser =
        User.builder()
            .id("123")
            .username("newUsername")
            .email("newEmail@example.com")
            .password("hashedNewPassword")
            .build();

    when(userService.updateUser(any(UpdateUserRequestDto.class))).thenReturn(updatedUser);

    ResponseEntity<UserResponseDto> response = userController.updateUser(updateUserRequestDto);

    assertNotNull(response);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("newUsername", response.getBody().getUsername());
    assertEquals("newEmail@example.com", response.getBody().getEmail());
    verify(userService, times(1)).updateUser(any(UpdateUserRequestDto.class));
  }

  @Test
  void loginShouldReturnSuccessWhenCredentialsAreValid() {
    LoginRequestDto loginRequest = new LoginRequestDto("mattlol85", "testPassword123");
    User user =
        User.builder()
            .username("mattlol85")
            .email("test@example.com")
            .password("$2a$10$hashedPassword")
            .build();

    when(userService.verifyPassword("mattlol85", "testPassword123")).thenReturn(true);
    when(userService.readByUsername("mattlol85")).thenReturn(user);

    ResponseEntity<LoginResponseDto> response = userController.login(loginRequest);

    assertNotNull(response);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().isSuccess());
    assertEquals("Login successful", response.getBody().getMessage());
    assertEquals("mattlol85", response.getBody().getUsername());
    assertEquals("test@example.com", response.getBody().getEmail());
    verify(userService, times(1)).verifyPassword("mattlol85", "testPassword123");
  }

  @Test
  void loginShouldReturnFailureWhenCredentialsAreInvalid() {
    LoginRequestDto loginRequest = new LoginRequestDto("mattlol85", "wrongPassword");

    when(userService.verifyPassword("mattlol85", "wrongPassword")).thenReturn(false);

    ResponseEntity<LoginResponseDto> response = userController.login(loginRequest);

    assertNotNull(response);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertFalse(response.getBody().isSuccess());
    assertEquals("Invalid username or password", response.getBody().getMessage());
    assertNull(response.getBody().getUsername());
    assertNull(response.getBody().getEmail());
    verify(userService, times(1)).verifyPassword("mattlol85", "wrongPassword");
    verify(userService, times(0)).readByUsername(any());
  }
}
