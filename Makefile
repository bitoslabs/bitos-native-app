.PHONY: doctor check native-test build-ios ios-build ios-install install-ios android-test build-android-apk android-apk android-install install-android service-test infra-check format-check clean-cache clean

doctor:
	./scripts/doctor.sh

check:
	./scripts/check.sh

native-test:
	./scripts/native-test.sh

build-ios:
	./scripts/ios-build.sh

ios-build: build-ios

ios-install:
	./scripts/ios-install.sh

install-ios: ios-install

android-test:
	./scripts/android-test.sh

build-android-apk:
	./scripts/android-apk.sh

android-apk: build-android-apk

android-install:
	./scripts/android-install.sh

install-android: android-install

service-test:
	npm run test:services

infra-check:
	docker compose -f infra/compose.yaml config --quiet

format-check:
	npm run format:check

clean-cache:
	./scripts/clean-cache.sh

clean: clean-cache
