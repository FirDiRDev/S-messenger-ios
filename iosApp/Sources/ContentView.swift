import SwiftUI
import UIKit
import Shared

/// Экран на Compose Multiplatform (shared/src/iosMain/.../MainViewController.kt).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all) // отступы от «чёлки» и home-индикатора считает сам Compose (WindowInsets.safeDrawing)
    }
}
