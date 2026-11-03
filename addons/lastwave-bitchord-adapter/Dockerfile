FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY server.mjs ./
COPY hybrid.mjs ./
EXPOSE 3000
CMD ["npm", "start"]
