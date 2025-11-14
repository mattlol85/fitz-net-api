package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest {

  @Mock private UserRepository userRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @InjectMocks private UserService userService;

  private AutoCloseable mocks;

  @BeforeEach
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void saveUserShouldSaveAndReturnUser() {
    User user =
        User.builder()
            .username("mattlol85")
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userRepository.save(any(User.class))).thenReturn(user);

    User savedUser = userService.saveUser(user);

    assertNotNull(savedUser);
    assertEquals("mattlol85", savedUser.getUsername());
    verify(userRepository, times(1)).save(any(User.class));
  }

  @Test
  void deleteUserShouldDeleteUserByUsername() {
    String username = "mattlol85";

    doNothing().when(userRepository).deleteByUsername(username);

    userService.deleteUser(username);

    verify(userRepository, times(1)).deleteByUsername(username);
  }

  @Test
  void readByUsernameShouldReturnUserWhenUserExists() {
    String username = "mattlol85";
    User user =
        User.builder()
            .username(username)
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userRepository.findByUsername(username)).thenReturn(user);

    User foundUser = userService.readByUsername(username);

    assertNotNull(foundUser);
    assertEquals(username, foundUser.getUsername());
    verify(userRepository, times(1)).findByUsername(username);
  }

  @Test
  void readByUsernameShouldReturnNullWhenUserDoesNotExist() {
    String username = "unknownUser";

    when(userRepository.findByUsername(username)).thenReturn(null);

    User foundUser = userService.readByUsername(username);

    assertNull(foundUser);
    verify(userRepository, times(1)).findByUsername(username);
  }

  @Test
  void updateUserShouldUpdateUsernameWhenUserExists() {
    String oldUsername = "mattlol85";
    String newUsername = "mattnew85";
    User user =
        User.builder()
            .username(oldUsername)
            .email("test@example.com")
            .password("$2a$10$hashedPassword")
            .build();
    UpdateUserRequestDto updateUserRequestDto =
        new UpdateUserRequestDto(oldUsername, newUsername, null, null, null);

    when(userRepository.findByUsername(oldUsername)).thenReturn(user);
    when(userRepository.save(any(User.class))).thenReturn(user);

    userService.updateUser(updateUserRequestDto);

    assertEquals(newUsername, user.getUsername());
    verify(userRepository, times(1)).findByUsername(oldUsername);
    verify(userRepository, times(1)).save(any(User.class));
  }

  @Test
  void updateUserShouldUpdatePasswordWhenUserExists() {
    String username = "mattlol85";
    String newPassword = "newPassword123";
    User user =
        User.builder()
            .username(username)
            .email("test@example.com")
            .password("$2a$10$oldHashedPassword")
            .build();
    UpdateUserRequestDto updateUserRequestDto =
        new UpdateUserRequestDto(username, null, null, null, newPassword);

    when(userRepository.findByUsername(username)).thenReturn(user);
    when(passwordEncoder.encode(newPassword)).thenReturn("$2a$10$newHashedPassword");
    when(userRepository.save(any(User.class))).thenReturn(user);

    userService.updateUser(updateUserRequestDto);

    verify(passwordEncoder, times(1)).encode(newPassword);
    verify(userRepository, times(1)).findByUsername(username);
    verify(userRepository, times(1)).save(any(User.class));
  }

  @Test
  void updateUserShouldDoNothingWhenUserDoesNotExist() {
    String oldUsername = "unknownUser";
    String newUsername = "newUser";
    UpdateUserRequestDto updateUserRequestDto =
        new UpdateUserRequestDto(oldUsername, newUsername, null, null, null);

    when(userRepository.findByUsername(oldUsername)).thenReturn(null);

    userService.updateUser(updateUserRequestDto);

    verify(userRepository, times(1)).findByUsername(oldUsername);
    verify(userRepository, times(0)).save(any(User.class));
  }

  @Test
  void verifyPasswordShouldReturnTrueWhenPasswordMatches() {
    String username = "mattlol85";
    String rawPassword = "testPassword123";
    User user =
        User.builder()
            .username(username)
            .email("test@example.com")
            .password("$2a$10$hashedPassword")
            .build();

    when(userRepository.findByUsername(username)).thenReturn(user);
    when(passwordEncoder.matches(rawPassword, "$2a$10$hashedPassword")).thenReturn(true);

    boolean result = userService.verifyPassword(username, rawPassword);

    assertTrue(result);
    verify(userRepository, times(1)).findByUsername(username);
    verify(passwordEncoder, times(1)).matches(rawPassword, "$2a$10$hashedPassword");
  }

  @Test
  void verifyPasswordShouldReturnFalseWhenPasswordDoesNotMatch() {
    String username = "mattlol85";
    String rawPassword = "wrongPassword";
    User user =
        User.builder()
            .username(username)
            .email("test@example.com")
            .password("$2a$10$hashedPassword")
            .build();

    when(userRepository.findByUsername(username)).thenReturn(user);
    when(passwordEncoder.matches(rawPassword, "$2a$10$hashedPassword")).thenReturn(false);

    boolean result = userService.verifyPassword(username, rawPassword);

    assertFalse(result);
    verify(userRepository, times(1)).findByUsername(username);
    verify(passwordEncoder, times(1)).matches(rawPassword, "$2a$10$hashedPassword");
  }

  @Test
  void verifyPasswordShouldReturnFalseWhenUserDoesNotExist() {
    String username = "unknownUser";
    String rawPassword = "testPassword123";

    when(userRepository.findByUsername(username)).thenReturn(null);

    boolean result = userService.verifyPassword(username, rawPassword);

    assertFalse(result);
    verify(userRepository, times(1)).findByUsername(username);
    verify(passwordEncoder, times(0)).matches(any(), any());
  }

  @Test
  void findAllsShouldReturnListOfUsers() {
    User user =
        User.builder()
            .username("mattlol85")
            .email("test@example.com")
            .password("testPassword")
            .build();

    when(userRepository.findAll()).thenReturn(Collections.singletonList(user));

    List<User> users = userService.findAll();

    assertNotNull(users);
    assertEquals(1, users.size());
    assertEquals("mattlol85", users.getFirst().getUsername());
    verify(userRepository, times(1)).findAll();
  }
}
