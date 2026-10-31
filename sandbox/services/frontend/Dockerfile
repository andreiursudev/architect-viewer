FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Built by `sandbox/gradlew -p sandbox assemble` (or :services:frontend:npmBuild).
COPY dist/ /usr/share/nginx/html/
