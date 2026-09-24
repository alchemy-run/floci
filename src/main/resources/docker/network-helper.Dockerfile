FROM alpine:3.21
RUN apk add --no-cache nftables iproute2 socat curl python3
LABEL io.floci.network-helper.version="2"
CMD ["sleep", "2147483647"]
