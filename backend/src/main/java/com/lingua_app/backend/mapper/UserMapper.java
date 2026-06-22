package com.lingua_app.backend.mapper;

import com.lingua_app.backend.dto.UserDto;
import com.lingua_app.backend.entity.User;

// Mapper classes convert between entity objects (database layer) and DTOs (API/service layer).
// Keeping this logic here instead of inside the entity or DTO means neither layer needs
// to know about the other — a clean separation of concerns.
//
// Static methods are used because a mapper holds no state; there is nothing to inject or mock.
public class UserMapper {

    // Entity → DTO: strips sensitive fields (passwordHash) before the object
    // leaves the service layer. Records use positional constructors — field order matters.
    public static UserDto mapToUserDto(User user) {
        return new UserDto(
            user.getId(),
            user.getEmail(),
            user.getCreatedAt(),
            user.isActive()
        );
    }

    // mapToUser is intentionally absent: a UserDto cannot reconstruct a valid User entity
    // because it does not carry passwordHash. Entity creation happens in AuthService,
    // where the raw password is available to hash before persisting.
}
