.PHONY: doctor check native-test ios-build android-test service-test infra-check format-check clean

doctor:
	./scripts/doctor.sh

check:
	./scripts/check.sh

native-test:
	./scripts/native-test.sh

ios-build:
	./scripts/ios-build.sh

android-test:
	./scripts/android-test.sh

service-test:
	npm run test:services

infra-check:
	docker compose -f infra/compose.yaml config --quiet

format-check:
	npm run format:check

clean:
	cmake -E remove_directory build-native
	xcodebuild -project apps/ios/BitOS.xcodeproj -scheme BitOS clean >/dev/null
