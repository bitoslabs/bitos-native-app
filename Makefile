.PHONY: doctor check native-test build-ios ios-build android-test build-android-apk android-apk service-test infra-check format-check clean-cache clean

doctor:
	./scripts/doctor.sh

check:
	./scripts/check.sh

native-test:
	./scripts/native-test.sh

build-ios:
	./scripts/ios-build.sh

ios-build: build-ios

android-test:
	./scripts/android-test.sh

build-android-apk:
	./scripts/android-apk.sh

android-apk: build-android-apk

service-test:
	npm run test:services

infra-check:
	docker compose -f infra/compose.yaml config --quiet

format-check:
	npm run format:check

clean-cache:
	./scripts/clean-cache.sh

clean: clean-cache
