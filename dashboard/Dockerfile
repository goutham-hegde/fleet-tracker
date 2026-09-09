# The dashboard is the one image in this project Jib cannot build: there is no JVM in it and no
# main class. What ships is a directory of static files and something to serve them.
#
# Two stages, and the split is what keeps the runtime image small. The first stage has Node, the
# whole dependency tree and the TypeScript compiler -- some 400 MB of tooling that exists only to
# turn source into `dist`. The second stage starts again from a web server and copies in nothing
# but that directory, so none of the build tooling reaches the cluster or a registry.

# ---- build ------------------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Dependencies first, and only the two files that describe them. Docker caches a layer until one
# of its inputs changes, so copying the whole source tree before installing would reinstall every
# dependency on every source edit. This way `npm ci` re-runs only when the lockfile actually moves.
COPY package.json package-lock.json ./

# `npm ci`, not `npm install`. It installs exactly what the lockfile states and fails if
# package.json and the lockfile disagree, where `install` would quietly resolve a newer version
# and write a different lockfile -- which is the difference between a reproducible image and one
# that happens to work on the day it was built.
RUN npm ci

COPY . .

# Typechecks and bundles. `npm run build` is `tsc -b && vite build`, so a type error fails the
# image build rather than shipping a broken bundle.
RUN npm run build

# ---- runtime ----------------------------------------------------------------------------------
# nginx-unprivileged rather than the standard nginx image: the same server, configured to run as a
# non-root user and to listen on 8080 rather than 80. Ports below 1024 are privileged, which is why
# the ordinary nginx image starts as root -- and a container serving four megabytes of static files
# has no business being root to do it.
FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /build/dist /usr/share/nginx/html

EXPOSE 8080
