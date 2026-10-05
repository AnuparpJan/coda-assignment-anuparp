package org.example.codakvstore.node;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Tags every response with the id of the node that served it, so routing is visible in a demo. */
@Component
public class NodeIdFilter extends OncePerRequestFilter {

    public static final String NODE_HEADER = "X-Kv-Node";

    private final String nodeId;

    public NodeIdFilter(@Value("${kv.node-id}") String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(NODE_HEADER, nodeId);
        chain.doFilter(request, response);
    }
}
