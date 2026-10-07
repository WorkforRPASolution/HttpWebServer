import com.sec.eeg.ars.actor.Master
import com.sec.eeg.ars.endpoint.HttpEndPoint
import org.scalatra.LifeCycle

import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context mount (new HttpEndPoint(Master.system), "/*")
  }
}
