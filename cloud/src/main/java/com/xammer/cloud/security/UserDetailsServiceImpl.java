// package com.xammer.cloud.security;

// import org.springframework.security.core.userdetails.User;
// import org.springframework.security.core.userdetails.UserDetails;
// import org.springframework.security.core.userdetails.UserDetailsService;
// import org.springframework.security.core.userdetails.UsernameNotFoundException;
// import org.springframework.stereotype.Service;

// import java.util.ArrayList;

// @Service
// public class UserDetailsServiceImpl implements UserDetailsService {

//     @Override
//     public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
//         // In a real application, you would load user details from a database.
//         // The password "password" is hashed here.
//         if ("admin".equals(username)) {
//             return new User("admin", "$2a$10$slYQmyNdGzTn7ZLBXBChFOC9f6kFjAqPhccnP6DxlWXx2lPk1C3G6", new ArrayList<>());
//         } else {
//             throw new UsernameNotFoundException("User not found with username: " + username);
//         }
//     }
// }