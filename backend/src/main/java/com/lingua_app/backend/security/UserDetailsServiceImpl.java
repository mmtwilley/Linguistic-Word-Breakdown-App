package com.lingua_app.backend.security;

import com.lingua_app.backend.entity.User;
import com.lingua_app.backend.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

// UserDetailsService is a Spring Security contract. Implementing it tells the framework
// how to look up a user by their username (email in this app) during authentication.
// Spring's AuthenticationManager calls this automatically when verifying credentials.
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // loadUserByUsername is called with the value passed as "username" — in this app
    // that is always the email address. Spring Security uses the returned UserDetails
    // to verify the password and derive the user's granted authorities.
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        // Spring's built-in User builder constructs a UserDetails object.
        // We pass an empty authorities list because this app uses JWT claims for
        // authorization rather than Spring Security roles/authorities.
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash()) // BCrypt hash — Spring verifies it via PasswordEncoder
                .authorities(Collections.emptyList())
                .build();
    }
}
