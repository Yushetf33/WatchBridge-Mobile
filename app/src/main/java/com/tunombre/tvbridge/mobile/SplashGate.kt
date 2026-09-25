package com.tunombre.tvbridge.mobile

/**
 * Cuándo puede quitarse la pantalla de arranque con el logo (ver
 * MainActivity): en cuanto Inicio tiene ya algo que pintar (fase 1 de
 * HomeViewModel.refresh) o se muestra la pantalla de verificación. Además,
 * MainActivity la suelta a los 2 segundos pase lo que pase, para que un
 * fallo de red nunca deje el logo eterno.
 */
object SplashGate {
    @Volatile
    var ready: Boolean = false
}
