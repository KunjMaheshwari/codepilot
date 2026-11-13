package com.codepilot.controller;

import com.codepilot.dto.RepositoryDto;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepositoryRepository repositoryRepository;

    @MockBean
    private UserRepository userRepository;

    private User testUser;
    private Repository testRepository;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@users.noreply.github.com");

        testRepository = new Repository();
        testRepository.setId(UUID.randomUUID());
        testRepository.setName("test-repo");
        testRepository.setFullName("testuser/test-repo");
        testRepository.setGithubRepoId("github123");
        testRepository.setOwner(testUser);
        testRepository.setIsActive(true);
    }

    @Test
    void getRepository_ShouldReturnRepository_WhenAuthorized() throws Exception {
        when(userRepository.findByEmail("testuser@users.noreply.github.com")).thenReturn(Optional.of(testUser));
        when(repositoryRepository.findByIdAndOwnerId(testRepository.getId(), testUser.getId()))
                .thenReturn(Optional.of(testRepository));

        mockMvc.perform(get("/api/v1/repositories/" + testRepository.getId())
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("login", "testuser");
                            attrs.put("email", "testuser@users.noreply.github.com");
                        })))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("test-repo"));
    }

    @Test
    void getRepository_ShouldReturn404_WhenRepositoryBelongsToAnotherUser() throws Exception {
        when(userRepository.findByEmail("testuser@users.noreply.github.com")).thenReturn(Optional.of(testUser));
        when(repositoryRepository.findByIdAndOwnerId(testRepository.getId(), testUser.getId()))
                .thenReturn(Optional.empty()); // simulate that it doesn't belong to the user

        mockMvc.perform(get("/api/v1/repositories/" + testRepository.getId())
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("login", "testuser");
                            attrs.put("email", "testuser@users.noreply.github.com");
                        })))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Repository not found with id : '" + testRepository.getId() + "'"));
    }

    @Test
    void getRepository_ShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/repositories/" + testRepository.getId()))
                .andExpect(status().isUnauthorized());
    }
}
