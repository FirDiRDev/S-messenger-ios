import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Регистрация фоновых задач, наблюдение за сетью, музыка темы.
        // Должно выполниться до конца didFinishLaunching - init() приложения для этого подходит.
        IosPlatform.shared.onLaunch()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
