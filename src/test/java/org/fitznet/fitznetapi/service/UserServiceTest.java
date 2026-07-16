package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private AutoCloseable closeable;
  private SimpleMeterRegistry meterRegistry;
  private UserService userService;

  @BeforeEach
  void setUp() {
    closeable = MockitoAnnotations.openMocks(this);
    meterRegistry = new SimpleMeterRegistry();
    userService = new UserService(userRepository, passwordEncoder, new FitzNetMetrics(meterRegistry));
  }

  @AfterEach
  void tearDown() throws Exception {
    closeable.close();
    meterRegistry.close();
  }

  @Test
  void shouldSaveUserAndRecordCreateMetrics() {
    User user =
        User.builder().username("testuser").password("password").email("test@example.com").build();

    when(passwordEncoder.encode("password")).thenReturn("encodedPassword");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User savedUser = userService.saveUser(user);

    assertNotNull(savedUser);
    assertEquals("testuser", savedUser.getUsername());
    assertEquals("encodedPassword", savedUser.getPassword());
    verify(passwordEncoder, times(1)).encode("password");
    verify(userRepository, times(1)).save(any(User.class));
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "create", "result", "success");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "create", "result", "success");
  }

  @Test
  void shouldAssignBoardColorWhenNotSet() {
    User user =
        User.builder().username("newuser").email("new@example.com").password("plainPass").build();

    when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User saved = userService.saveUser(user);

    assertNotNull(saved.getBoardColor());
    assertTrue(saved.getBoardColor().matches("hsl\\(\\d+,72%,50%\\)"));
  }

  @Test
  void shouldPreserveExistingBoardColor() {
    User user =
        User.builder()
            .username("existing")
            .email("existing@example.com")
            .password("plainPass")
            .boardColor("hsl(42,72%,50%)")
            .build();

    when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User saved = userService.saveUser(user);

    assertEquals("hsl(42,72%,50%)", saved.getBoardColor());
  }

  @Test
  void shouldDeleteUserAndRecordMetric() {
    doNothing().when(userRepository).deleteByUsername("testuser");

    userService.deleteUser("testuser");

    verify(userRepository, times(1)).deleteByUsername("testuser");
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "delete", "result", "success");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "delete", "result", "success");
  }

  @Test
  void shouldReadByUsernameWhenUserExists() {
    User user =
        User.builder().username("testuser").password("password").email("test@example.com").build();
    when(userRepository.findByUsername("testuser")).thenReturn(user);

    User foundUser = userService.readByUsername("testuser");

    assertNotNull(foundUser);
    assertEquals("testuser", foundUser.getUsername());
    verify(userRepository, times(1)).findByUsername("testuser");
  }

  @Test
  void shouldReadByUsernameWhenUserDoesNotExist() {
    when(userRepository.findByUsername("missinguser")).thenReturn(null);

    User foundUser = userService.readByUsername("missinguser");

    assertNull(foundUser);
    verify(userRepository, times(1)).findByUsername("missinguser");
  }

  @Test
  void shouldUpdateUserAndRecordMetric() {
    UpdateUserRequestDto updateRequest =
        new UpdateUserRequestDto("testuser", "updateduser", "updated@example.com", "newpassword");
    User updatedUser =
        User.builder()
            .username("updateduser")
            .password("newpassword")
            .email("updated@example.com")
            .build();
    when(userRepository.findAndModifyUser(updateRequest)).thenReturn(updatedUser);

    User result = userService.updateUser(updateRequest);

    assertNotNull(result);
    assertEquals("updateduser", result.getUsername());
    assertEquals("updated@example.com", result.getEmail());
    verify(userRepository, times(1)).findAndModifyUser(updateRequest);
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "update", "result", "success");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "update", "result", "success");
  }

  @Test
  void shouldReturnNullWhenUserDoesNotExistOnUpdateAndRecordMetric() {
    UpdateUserRequestDto updateRequest =
        new UpdateUserRequestDto("missinguser", null, null, null);
    when(userRepository.findAndModifyUser(updateRequest)).thenReturn(null);

    User result = userService.updateUser(updateRequest);

    assertNull(result);
    verify(userRepository, times(1)).findAndModifyUser(updateRequest);
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "update", "result", "no_update");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "update", "result", "no_update");
  }

  @Test
  void shouldVerifyPasswordWhenPasswordMatchesAndRecordMetric() {
    User user =
        User.builder()
            .username("testuser")
            .password("encodedPassword")
            .email("test@example.com")
            .build();
    when(userRepository.findByUsername("testuser")).thenReturn(user);
    when(passwordEncoder.matches("password", "encodedPassword")).thenReturn(true);

    boolean isMatch = userService.verifyPassword("testuser", "password");

    assertTrue(isMatch);
    verify(userRepository, times(1)).findByUsername("testuser");
    verify(passwordEncoder, times(1)).matches("password", "encodedPassword");
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "login", "result", "success");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "login", "result", "success");
  }

  @Test
  void shouldReturnFalseWhenPasswordDoesNotMatchAndRecordMetric() {
    User user =
        User.builder()
            .username("testuser")
            .password("encodedPassword")
            .email("test@example.com")
            .build();
    when(userRepository.findByUsername("testuser")).thenReturn(user);
    when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

    boolean isMatch = userService.verifyPassword("testuser", "wrongPassword");

    assertFalse(isMatch);
    verify(userRepository, times(1)).findByUsername("testuser");
    verify(passwordEncoder, times(1)).matches("wrongPassword", "encodedPassword");
    assertCounterCount(
        "fitznet.user.operations", 1.0, "operation", "login", "result", "invalid_credentials");
    assertTimerCount(
        "fitznet.user.operation", 1L, "operation", "login", "result", "invalid_credentials");
  }

  @Test
  void shouldReturnFalseWhenUserDoesNotExistAndRecordMetric() {
    when(userRepository.findByUsername("missinguser")).thenReturn(null);

    boolean isMatch = userService.verifyPassword("missinguser", "password");

    assertFalse(isMatch);
    verify(userRepository, times(1)).findByUsername("missinguser");
    assertCounterCount("fitznet.user.operations", 1.0, "operation", "login", "result", "user_not_found");
    assertTimerCount("fitznet.user.operation", 1L, "operation", "login", "result", "user_not_found");
  }

  @Test
  void shouldReturnAllUsers() {
    User user1 =
        User.builder().username("user1").password("password1").email("user1@example.com").build();
    User user2 =
        User.builder().username("user2").password("password2").email("user2@example.com").build();
    when(userRepository.findAll()).thenReturn(List.of(user1, user2));

    List<User> users = userService.findAll();

    assertEquals(2, users.size());
    assertEquals("user1", users.get(0).getUsername());
    assertEquals("user2", users.get(1).getUsername());
    verify(userRepository, times(1)).findAll();
  }

  private void assertCounterCount(String name, double expectedCount, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    assertNotNull(counter);
    assertEquals(expectedCount, counter.count());
  }

  private void assertTimerCount(String name, long expectedCount, String... tags) {
    Timer timer = meterRegistry.find(name).tags(tags).timer();
    assertNotNull(timer);
    assertEquals(expectedCount, timer.count());
  }
}
