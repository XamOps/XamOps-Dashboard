package com.xammer.cloud.config;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(ClientRepository clientRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if any clients already exist to prevent re-initialization
        if (clientRepository.count() == 0) {
            logger.info("No clients found in the database. Initializing default client and user...");

            // 1. Create the first client
            Client defaultClient = new Client("Default Client");
            clientRepository.save(defaultClient);
            logger.info("Successfully created client: {}", defaultClient.getName());

            // 2. Create the first user for this client
            String username = "admin";
            String password = "password"; // You can change this default password

            User adminUser = new User(
                username,
                passwordEncoder.encode(password),
                defaultClient
            );
            userRepository.save(adminUser);

            logger.info("========================================================================");
            logger.info("Default admin user created. Please use these credentials to log in:");
            logger.info("Username: {}", username);
            logger.info("Password: {}", password);
            logger.info("========================================================================");
        } else {
            logger.info("Database already contains client data. Skipping initialization.");
        }
    }
}
