// package com.xammer.cloud.security;

// import io.jsonwebtoken.Claims;
// import io.jsonwebtoken.Jwts;
// import io.jsonwebtoken.SignatureAlgorithm;
// import io.jsonwebtoken.io.Decoders;
// import io.jsonwebtoken.security.Keys;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.security.core.userdetails.UserDetails;
// import org.springframework.stereotype.Component;
// import javax.crypto.SecretKey;
// import java.util.Date;
// import java.util.HashMap;
// import java.util.Map;
// import java.util.function.Function;

// @Component
// public class JwtTokenProvider {

//     @Value("${jwt.secret}")
//     private String secret;
//     @Value("${jwt.expiration}")
//     private long jwtExpirationInMs;

//     private SecretKey getSigningKey() {
//         byte[] keyBytes = Decoders.BASE64.decode(this.secret);
//         return Keys.hmacShaKeyFor(keyBytes);
//     }

//     public String generateToken(UserDetails userDetails) {
//         return Jwts.builder()
//                 .setSubject(userDetails.getUsername())
//                 .setIssuedAt(new Date(System.currentTimeMillis()))
//                 .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationInMs))
//                 .signWith(getSigningKey(), SignatureAlgorithm.HS256)
//                 .compact();
//     }

//     public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
//         final Claims claims = Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
//         return claimsResolver.apply(claims);
//     }

//     public String getUsernameFromToken(String token) {
//         return getClaimFromToken(token, Claims::getSubject);
//     }

//     public boolean validateToken(String token, UserDetails userDetails) {
//         final String username = getUsernameFromToken(token);
//         return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
//     }

//     private boolean isTokenExpired(String token) {
//         return getClaimFromToken(token, Claims::getExpiration).before(new Date());
//     }
// }