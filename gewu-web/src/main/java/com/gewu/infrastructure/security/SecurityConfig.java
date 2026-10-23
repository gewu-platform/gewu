package com.gewu.infrastructure.security;

import com.gewu.security.JwtAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置类（对齐 java-ai-native SecurityConfig）
 * 配置应用的安全策略和JWT认证
 *
 * 放行策略（过渡期）：学习示例与旧对话 API 保持匿名可用，
 * 新增的 /api/user/** 要求认证；P3 对话模块对齐时再收紧。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    /**
     * 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Dao认证提供者
     */
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * 配置安全过滤链
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用session，使用JWT进行身份验证
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // 设置未认证访问处理器
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
            )
            // 授权配置（对齐 native：健康检查/公开端点放行，其余全部认证）
            .authorizeHttpRequests(authz -> authz
                // ASYNC dispatch（SseEmitter 完成后的内部转发）直接放行；ERROR dispatch（错误页转发）同样放行，
                // 否则 403 AccessDenied 转发 /error 时被 JWT 过滤器拦成 401，掩盖真实拒绝原因
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/**").permitAll()      // 认证接口无需认证
                .requestMatchers("/api/health/**").permitAll()    // 健康检查无需认证
                .requestMatchers("/api/chat/health").permitAll()  // 聊天健康检查无需认证
                .requestMatchers("/api/agent/health").permitAll() // Agent 健康检查无需认证
                .requestMatchers("/api/agent/tools").permitAll()  // Agent 工具列表无需认证
                // P8b 权限管控：Agent 定义管理与工具审批仅管理员（ROLE_ADMIN）
                .requestMatchers("/api/agent/definitions/**").hasRole("ADMIN")
                .requestMatchers("/api/agent/approval/approve").hasRole("ADMIN")
                .requestMatchers("/api/agent/approval/reject").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ACTUATOR") // 执行器需要 ACTUATOR 角色（对齐 native）
                .anyRequest().authenticated()                     // 其他请求必须认证（含 /api/chat/**、/api/service/**、/api/user/**）
            )
            // 禁用CSRF，适用于API应用
            .csrf(csrf -> csrf
                .disable()
            )
            // 添加JWT过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
