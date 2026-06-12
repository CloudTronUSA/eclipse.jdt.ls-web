package org.eclipse.jdt.ls.web.internal.teavm;

import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public final class WebJdtLsTeaVMPlugin implements TeaVMPlugin {

	@Override
	public void install(TeaVMHost host) {
		host.add(new EcjMessagesTransformer());
		host.add(new ProcessingPreprocessorTransformer());
	}
}
