package org.fitznet.fitznetapi.service.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.fitznet.fitznetapi.dto.responses.AttachmentContentDto;
import org.fitznet.fitznetapi.dto.responses.AttachmentDto;
import org.fitznet.fitznetapi.model.AiAttachment;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.MessageAttachment;
import org.fitznet.fitznetapi.repository.AiAttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Image attachments (contract section 10). An upload is stored unattached with {@code expiresAt}
 * set (a Mongo TTL index removes it after {@code ai.chat.attachment-orphan-ttl} if nothing uses it);
 * attaching it to a message clears {@code expiresAt}. Only png, jpeg and webp are accepted, the
 * bytes must really start with that format's magic number, and the decoded size is capped at
 * {@code ai.chat.max-attachment-bytes}. Owner-only: anyone else gets a 404. Image bytes, names and
 * base64 are never logged.
 */
@Service
public class AttachmentService {

  private static final Logger logger = LoggerFactory.getLogger(AttachmentService.class);

  private final AiAttachmentRepository attachments;
  private final int maxBytes;
  private final int maxPerMessage;
  private final Duration orphanTtl;

  public AttachmentService(
      AiAttachmentRepository attachments,
      @Value("${ai.chat.max-attachment-bytes:3145728}") int maxBytes,
      @Value("${ai.chat.max-attachments-per-message:4}") int maxPerMessage,
      @Value("${ai.chat.attachment-orphan-ttl:24h}") Duration orphanTtl) {
    this.attachments = attachments;
    this.maxBytes = maxBytes;
    this.maxPerMessage = maxPerMessage;
    this.orphanTtl = orphanTtl;
  }

  // ---- upload and download -----------------------------------------------------------------

  /** Decodes the REST body's base64; 400 when it is invalid or obviously larger than the limit. */
  public byte[] decodeBase64(String dataBase64) {
    if (dataBase64 == null || dataBase64.isBlank()) {
      throw badRequest("dataBase64 must not be empty");
    }
    // 4 base64 characters carry 3 bytes; refuse before allocating the decoded copy.
    if (dataBase64.length() > (long) (maxBytes / 3 + 1) * 4 + 4) {
      throw tooLarge();
    }
    try {
      return Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException ex) {
      throw badRequest("dataBase64 is not valid base64");
    }
  }

  public AttachmentDto upload(String username, String name, String mimeType, byte[] data) {
    if (name == null || name.isBlank()) {
      throw badRequest("name must not be blank");
    }
    if (name.length() > 200) {
      throw badRequest("name must be at most 200 characters");
    }
    if (mimeType == null || !isAllowedType(mimeType)) {
      throw badRequest("Only image/png, image/jpeg and image/webp are accepted");
    }
    if (data == null || data.length == 0) {
      throw badRequest("The image is empty");
    }
    if (data.length > maxBytes) {
      throw tooLarge();
    }
    if (!matchesMagic(mimeType, data)) {
      throw badRequest("The data is not a valid " + mimeType + " image");
    }
    Instant now = Instant.now();
    AiAttachment saved =
        attachments.save(
            AiAttachment.builder()
                .ownerUsername(username)
                .kind(AiAttachment.KIND_IMAGE)
                .mimeType(mimeType)
                .name(name.trim())
                .sizeBytes(data.length)
                .data(data)
                .createdAt(now)
                .expiresAt(now.plus(orphanTtl))
                .build());
    logger.info("Stored attachment {} ({} bytes, {})", saved.getId(), saved.getSizeBytes(), mimeType);
    return toDto(saved);
  }

  /** The bytes and content type of the caller's own attachment (404 otherwise). */
  public AttachmentContentDto read(String username, String id) {
    AiAttachment attachment =
        attachments
            .findByIdAndOwnerUsername(id, username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    return new AttachmentContentDto(attachment.getMimeType(), attachment.getData());
  }

  // ---- use in turns ------------------------------------------------------------------------

  /**
   * Resolves the ids of a new message: every id must exist, belong to the caller and not be
   * attached to a message already; at most {@code ai.chat.max-attachments-per-message}.
   */
  public List<AiAttachment> resolveForNewMessage(String username, List<String> ids) {
    return resolve(username, ids, null);
  }

  /**
   * Like {@link #resolveForNewMessage}, but an attachment already on {@code message} may be kept
   * (an edit that sends the ids it wants to retain).
   */
  public List<AiAttachment> resolveForEdit(String username, List<String> ids, AiMessage message) {
    return resolve(username, ids, message.getId());
  }

  private List<AiAttachment> resolve(String username, List<String> ids, String ownMessageId) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    if (ids.size() > maxPerMessage) {
      throw badRequest("At most " + maxPerMessage + " attachments per message");
    }
    Set<String> distinct = new LinkedHashSet<>(ids);
    if (distinct.size() != ids.size()) {
      throw badRequest("Duplicate attachment id");
    }
    Map<String, AiAttachment> found =
        attachments.findAllById(distinct).stream()
            .collect(Collectors.toMap(AiAttachment::getId, Function.identity()));
    List<AiAttachment> resolved = new ArrayList<>();
    for (String id : ids) {
      AiAttachment attachment = found.get(id);
      if (attachment == null || !username.equals(attachment.getOwnerUsername())) {
        throw badRequest("Unknown attachment id");
      }
      if (attachment.attached() && !attachment.getMessageId().equals(ownMessageId)) {
        throw badRequest("Attachment is already used by another message");
      }
      resolved.add(attachment);
    }
    return resolved;
  }

  /** Attaches the (not yet attached) attachments to the message and stops their expiry. */
  public void attach(List<AiAttachment> toAttach, String conversationId, String messageId) {
    List<AiAttachment> changed = new ArrayList<>();
    for (AiAttachment attachment : toAttach) {
      if (!attachment.attached()) {
        attachment.setConversationId(conversationId);
        attachment.setMessageId(messageId);
        attachment.setExpiresAt(null);
        changed.add(attachment);
      }
    }
    if (!changed.isEmpty()) {
      attachments.saveAll(changed);
    }
  }

  public void deleteAll(Collection<String> ids) {
    if (!ids.isEmpty()) {
      attachments.deleteAllById(ids);
    }
  }

  public void deleteForConversation(String conversationId, Collection<String> ids) {
    attachments.deleteByConversationId(conversationId);
    deleteAll(ids);
  }

  /**
   * The base64 encoding of each attachment, read in one query. Ids without a stored attachment are
   * absent from the result.
   */
  public Map<String, String> loadImages(Collection<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<String, String> images = new HashMap<>();
    for (AiAttachment attachment : attachments.findAllById(ids)) {
      if (attachment.getData() != null) {
        images.put(attachment.getId(), Base64.getEncoder().encodeToString(attachment.getData()));
      }
    }
    return images;
  }

  public static MessageAttachment ref(AiAttachment attachment) {
    return new MessageAttachment(
        attachment.getId(),
        attachment.getKind(),
        attachment.getMimeType(),
        attachment.getSizeBytes(),
        attachment.getName());
  }

  /** Ids of the attachments the given messages reference. */
  public static Set<String> idsOf(List<AiMessage> messages) {
    Set<String> ids = new HashSet<>();
    for (AiMessage message : messages) {
      if (message.getAttachments() != null) {
        message.getAttachments().forEach(a -> ids.add(a.id()));
      }
    }
    return ids;
  }

  // ---- validation helpers ------------------------------------------------------------------

  private static boolean isAllowedType(String mimeType) {
    return mimeType.equals("image/png") || mimeType.equals("image/jpeg") || mimeType.equals("image/webp");
  }

  /** Does the content really start with the magic number of the declared format? */
  static boolean matchesMagic(String mimeType, byte[] data) {
    return switch (mimeType) {
      case "image/png" ->
          startsWith(data, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
      case "image/jpeg" -> startsWith(data, 0, 0xFF, 0xD8, 0xFF);
      case "image/webp" ->
          startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P');
      default -> false;
    };
  }

  private static boolean startsWith(byte[] data, int offset, int... expected) {
    if (data.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((data[offset + i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }

  private ResponseStatusException tooLarge() {
    return badRequest("The image is larger than " + maxBytes + " bytes");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static AttachmentDto toDto(AiAttachment a) {
    return new AttachmentDto(a.getId(), a.getKind(), a.getMimeType(), a.getSizeBytes(), a.getName());
  }
}
