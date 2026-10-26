package ottv2.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import ottv2.api.auth.AuthDtos.CsrfResponse;
import ottv2.api.auth.AuthDtos.LoginRequest;
import ottv2.api.auth.AuthDtos.RegisterRequest;
import ottv2.api.auth.AuthDtos.UserResponse;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.security.TokenSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final TokenSessionService tokenSessionService;

    public AuthController(AuthService authService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          TokenSessionService tokenSessionService) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.tokenSessionService = tokenSessionService;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    @GetMapping("/health")
    public java.util.Map<String, String> health() {
        return java.util.Map.of("status", "UP", "service", "ottv2-api");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        
        // Đảm bảo session tồn tại trước khi đổi ID để chống Session Fixation
        jakarta.servlet.http.HttpSession existingSession = httpRequest.getSession(false);
        if (existingSession != null) {
            httpRequest.changeSessionId();
        }
        jakarta.servlet.http.HttpSession session = httpRequest.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // 1. Lưu context vào HttpSession cho cả Spring Security Filter và Servlet Container
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // 2. Cung cấp Auth Token qua Header & Body (Dual Auth: Hỗ trợ 100% khi trình duyệt chặn 3rd-party cookie / Incognito)
        ApiPrincipal principal = (ApiPrincipal) authentication.getPrincipal();
        String token = tokenSessionService.createToken(principal);
        httpResponse.setHeader("X-Auth-Token", token);
        httpResponse.setHeader("Authorization", "Bearer " + token);

        UserResponse user = authService.getUser(principal.id());
        return new UserResponse(
                user.id(),
                user.studentCode(),
                user.email(),
                user.displayName(),
                user.role(),
                user.pvpElo(),
                user.botElo(),
                token
        );
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        ApiPrincipal principal = (ApiPrincipal) authentication.getPrincipal();
        return authService.getUser(principal.id());
    }
}
