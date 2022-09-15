FROM golang as build-env
COPY . /highlighter
WORKDIR /highlighter
RUN ["go", "build", "-tags", "netgo"]

FROM scratch
COPY --from=build-env /highlighter/highlighter /highlighter
EXPOSE 8890
ENTRYPOINT ["./highlighter"]
