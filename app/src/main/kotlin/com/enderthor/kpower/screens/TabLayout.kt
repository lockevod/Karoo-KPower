package com.enderthor.kpower.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview


@Composable
fun TabLayout(
) {
    // La ayuda en-app se eliminó (estaba desfasada); la documentación vive en el README de
    // GitHub. Esta pantalla muestra directamente la configuración de potencia.
    Column(modifier = Modifier.fillMaxSize()) {
        ConfigDataAppNavHost()
    }
}

@Preview(name = "karoo", device = "spec:width=480px,height=800px,dpi=300")
@Composable
private fun PreviewTabLayout() {
    TabLayout(
    )
}
