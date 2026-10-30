FROM nginx:alpine

# Upgrade Alpine packages to patched versions
RUN apk update && \
    apk upgrade --no-cache && \
    rm -f /usr/share/nginx/html/index.html

COPY index.html /usr/share/nginx/html/index.html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
