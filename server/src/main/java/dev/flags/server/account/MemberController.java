package dev.flags.server.account;

import dev.flags.server.account.AccountDtos.AddMemberRequest;
import dev.flags.server.account.AccountDtos.ChangeRoleRequest;
import dev.flags.server.account.AccountDtos.UserView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
class MemberController {

    private final AccountService accounts;

    MemberController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<UserView> list() {
        return accounts.members();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView add(@Valid @RequestBody AddMemberRequest request) {
        return accounts.addMember(request);
    }

    @PutMapping("/{id}/role")
    UserView changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return accounts.changeRole(id, request.role());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id) {
        accounts.removeMember(id);
    }
}
