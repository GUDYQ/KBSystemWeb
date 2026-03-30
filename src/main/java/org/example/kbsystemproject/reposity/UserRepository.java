package org.example.kbsystemproject.reposity;

import org.example.kbsystemproject.entity.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, String> {

    Mono<User> findFirstByEmail(String email);

    Mono<User> findFirstByUsernameAndEmail(String username, String email);

}
