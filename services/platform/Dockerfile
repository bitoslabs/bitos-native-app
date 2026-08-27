FROM node:24.13.1-alpine3.23

RUN addgroup -S bitos && adduser -S -G bitos bitos
WORKDIR /app
COPY --chown=bitos:bitos src ./src
USER bitos
EXPOSE 8080
CMD ["node", "--experimental-strip-types", "src/api.ts"]
